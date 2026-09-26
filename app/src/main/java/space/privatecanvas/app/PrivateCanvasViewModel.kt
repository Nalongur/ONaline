package space.privatecanvas.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class PrivateCanvasViewModel(application: Application) : AndroidViewModel(application) {
    private val identity = DeviceIdentityManager(application)
    private val store = SecureLocalStore(application)
    private val relay = RelayClient(::handleRelayIncoming)
    private val migrationHttp = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(
        (store.load() ?: PrivateCanvasState()).let { loaded ->
            val migratedContacts = loaded.contacts.map { contact ->
                contact.copy(
                    verified = contact.verified && contact.identityPublicKey.isNotBlank(),
                    spaceId = contact.spaceId.ifBlank { UUID.randomUUID().toString() },
                )
            }
            val cleanedBlocks = loaded.blocksByContact.mapValues { (_, blocks) ->
                blocks.map { it.copy(clusterId = null) }
            }
            val migratedDocuments = loaded.textDocuments.toMutableMap()
            cleanedBlocks.values.flatten().forEach { block ->
                if (migratedDocuments[block.id].isNullOrEmpty() && block.text.isNotEmpty()) {
                    migratedDocuments[block.id] = initialTextAtoms(block.id, block.text)
                }
            }
            val renderedBlocks = cleanedBlocks.mapValues { (_, blocks) ->
                blocks.map { block ->
                    val atoms = migratedDocuments[block.id].orEmpty()
                    if (atoms.isEmpty()) block else block.copy(text = renderTextAtoms(atoms))
                }
            }
            val migratedServerUrl = if (BuildConfig.DEBUG && loaded.serverUrl == "ws://10.0.2.2:8787/v1/ws") {
                "ws://10.0.2.2:9876/v1/ws"
            } else {
                loaded.serverUrl
            }
            loaded.copy(
                contacts = migratedContacts,
                blocksByContact = renderedBlocks,
                eventsByContact = migratedContacts.associate { contact ->
                    contact.id to loaded.eventsByContact[contact.id].orEmpty()
                },
                textDocuments = migratedDocuments,
                serverUrl = migratedServerUrl
                    .takeUnless { !BuildConfig.DEBUG && it.startsWith("ws://", ignoreCase = true) }
                    .orEmpty(),
            )
        },
    )
    val state: StateFlow<PrivateCanvasState> = _state.asStateFlow()
    private var connectJob: Job? = null
    private var persistJob: Job? = null
    private var relaySyncJob: Job? = null
    private var clearRequestJob: Job? = null
    private var clearUndoJob: Job? = null
    private var reconnectLiveContactId: String? = null
    private val eventJobs = mutableMapOf<String, Job>()
    private val pendingTextDeltas = mutableMapOf<String, TextCrdtDelta>()
    private var appInForeground = true

    init {
        val notifications = application.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "ONaline activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Generic activity alerts without message content"
            },
        )
        state.value.pendingClearRequest?.let { scheduleClearRequestExpiry(it.requestId, it.expiresAtEpochMs) }
        state.value.clearUndo?.let { scheduleClearUndoExpiry(it.requestId, it.expiresAtEpochMs) }
        scheduleRelaySync(0)
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
    }

    fun createPairingInvite(): PairingInviteDisplay {
        val display = identity.createInvite(state.value.localDisplayName, state.value.serverUrl)
        val parsed = PairingInviteCodec.decodeAndVerify(display.token) as? PairingInviteResult.Valid
        if (parsed != null) {
            val invite = parsed.invite
            mutate {
                copy(
                    pendingInvites = (pendingInvites.filterNot { it.spaceId == invite.spaceId } + PendingInvite(
                        spaceId = invite.spaceId,
                        spaceKeyBase64 = invite.spaceKeyBase64,
                        serverUrl = invite.serverUrl,
                        expiresAtEpochMs = invite.expiresAtEpochMs,
                    )).takeLast(8),
                )
            }
            scheduleRelaySync(0)
        }
        return display
    }

    fun acceptPairingInvite(rawToken: String, localAlias: String): Boolean {
        val result = PairingInviteCodec.decodeAndVerify(rawToken)
        if (result is PairingInviteResult.Expired) {
            transient { copy(notice = "INVITE EXPIRED") }
            return false
        }
        if (result !is PairingInviteResult.Valid) {
            transient { copy(notice = "INVALID INVITE") }
            return false
        }
        val invite = result.invite
        if (invite.inviterDeviceId == identity.deviceId || state.value.contacts.any {
                it.remoteDeviceId == invite.inviterDeviceId || it.spaceId == invite.spaceId
            }
        ) {
            transient { copy(notice = "CONTACT ALREADY PAIRED") }
            return false
        }
        val name = normalizeDisplayName(localAlias.ifBlank { invite.inviterDisplayName })
        val id = UUID.randomUUID().toString()
        mutate {
            val contact = TrustedContact(
                id = id,
                displayName = name,
                verified = false,
                remoteDeviceId = invite.inviterDeviceId,
                identityPublicKey = invite.identityPublicKey,
                spaceId = invite.spaceId,
                safetyCode = PairingInviteCodec.safetyCode(identity.publicKeyBase64, invite.identityPublicKey, invite.spaceId),
                pairedAtEpochMs = System.currentTimeMillis(),
                spaceKeyBase64 = invite.spaceKeyBase64,
            )
            copy(
                contacts = contacts + contact,
                blocksByContact = blocksByContact + (id to emptyList()),
                framesByContact = framesByContact + (id to emptyList()),
                eventsByContact = eventsByContact + (id to emptyList()),
                serverUrl = invite.serverUrl.ifBlank { serverUrl },
                notice = "CONTACT PAIRED",
            ).appendEvent(id, CanvasEventType.ContactPaired, id, contactPayload(contact))
        }
        scheduleRelaySync(0)
        return true
    }

    fun updateLocalDisplayName(value: String) = mutate {
        copy(localDisplayName = normalizeDisplayName(value), notice = "DISPLAY NAME UPDATED")
    }

    fun renameContact(contactId: String, value: String) = mutate {
        copy(
            contacts = contacts.updateContact(contactId) { it.copy(displayName = normalizeDisplayName(value)) },
            notice = "CONTACT RENAMED",
        )
    }

    fun setContactPinned(contactId: String, pinned: Boolean) = mutate {
        copy(
            contacts = contacts.updateContact(contactId) { it.copy(pinned = pinned) },
            notice = if (pinned) "CONTACT PINNED" else "CONTACT UNPINNED",
        )
    }

    fun verifyContactSafetyCode(contactId: String) = mutate {
        copy(
            contacts = contacts.updateContact(contactId) { contact ->
                if (contact.safetyCode.isBlank()) contact else contact.copy(verified = true)
            },
            notice = "SAFETY CODE VERIFIED",
        )
    }

    fun deleteContact(contactId: String) {
        val removedBlockIds = state.value.blocksByContact[contactId].orEmpty().mapTo(mutableSetOf()) { it.id }
        mutate {
            copy(
                route = if (activeContactId == contactId) AppRoute.Contacts else route,
                activeContactId = if (activeContactId == contactId) null else activeContactId,
                contacts = contacts.filterNot { it.id == contactId },
                blocksByContact = blocksByContact - contactId,
                framesByContact = framesByContact - contactId,
                eventsByContact = eventsByContact - contactId,
                textDocuments = textDocuments.filterKeys { it !in removedBlockIds },
                hiddenDrafts = hiddenDrafts.filterKeys { it !in removedBlockIds },
                selectedBlockId = null,
                notice = "CONTACT DELETED",
            )
        }
    }

    fun setContactBlocked(contactId: String, blocked: Boolean) {
        if (blocked && reconnectLiveContactId == contactId) reconnectLiveContactId = null
        mutate {
            copy(
                route = if (blocked && activeContactId == contactId) AppRoute.Contacts else route,
                activeContactId = if (blocked && activeContactId == contactId) null else activeContactId,
                selectedBlockId = if (blocked && activeContactId == contactId) null else selectedBlockId,
                contacts = contacts.updateContact(contactId) {
                    it.copy(blocked = blocked, status = if (blocked) ConnectionStatus.Offline else ConnectionStatus.Available)
                },
                notice = if (blocked) "CONTACT BLOCKED" else "CONTACT UNBLOCKED",
            )
        }
        scheduleRelaySync(0)
    }

    fun connect(contactId: String) {
        val contact = state.value.contacts.firstOrNull { it.id == contactId } ?: return
        if (contact.blocked) {
            transient { copy(notice = "UNBLOCK CONTACT BEFORE CONNECTING") }
            return
        }
        if (!contact.verified || contact.identityPublicKey.isBlank()) {
            transient { copy(notice = "VERIFY SAFETY CODE BEFORE CONNECTING") }
            return
        }
        connectJob?.cancel()
        mutate {
            copy(contacts = contacts.updateContact(contactId) { it.copy(status = ConnectionStatus.Requesting) })
        }
        scheduleRelaySync(0)
        publishControl(contact, "connect_request", JSONObject().apply {
            put("fromDeviceId", identity.deviceId)
            put("sentAt", System.currentTimeMillis())
        })
        val timeoutMs = state.value.connectionRequestTimeoutSeconds * 1_000L
        connectJob = viewModelScope.launch {
            delay(timeoutMs)
            mutate {
                copy(
                    contacts = contacts.updateContact(contactId) { it.copy(status = ConnectionStatus.Available) },
                    notice = "NO RESPONSE",
                )
            }
        }
    }

    fun acceptIncomingConnection() {
        val contactId = state.value.incomingConnectionContactId ?: return
        val contact = state.value.contacts.firstOrNull { it.id == contactId } ?: return
        if (contact.blocked) {
            transient { copy(incomingConnectionContactId = null) }
            return
        }
        reconnectLiveContactId = contactId
        publishControl(contact, "connect_accept", JSONObject().put("fromDeviceId", identity.deviceId))
        mutate {
            copy(
                incomingConnectionContactId = null,
                contacts = contacts.updateContact(contactId) { it.copy(status = ConnectionStatus.Connected) },
                activeContactId = contactId,
                route = AppRoute.Canvas,
                canvasMode = CanvasMode.Live,
                selectedBlockId = null,
                notice = null,
            )
        }
    }

    fun declineIncomingConnection() {
        val contactId = state.value.incomingConnectionContactId ?: return
        val contact = state.value.contacts.firstOrNull { it.id == contactId } ?: return
        publishControl(contact, "connect_decline", JSONObject().put("fromDeviceId", identity.deviceId))
        transient { copy(incomingConnectionContactId = null) }
    }

    fun cancelConnection(contactId: String) {
        connectJob?.cancel()
        if (reconnectLiveContactId == contactId) reconnectLiveContactId = null
        mutate {
            copy(contacts = contacts.updateContact(contactId) { it.copy(status = ConnectionStatus.Available) })
        }
    }

    fun openOffline(contactId: String) {
        reconnectLiveContactId = null
        mutate {
            copy(
                activeContactId = contactId,
                route = AppRoute.Canvas,
                canvasMode = CanvasMode.Offline,
                selectedBlockId = null,
                contacts = contacts.updateContact(contactId) { it.copy(status = ConnectionStatus.Offline) },
            )
        }
    }

    fun openNotificationTarget(target: NotificationTarget) {
        val snapshot = state.value
        val contact = snapshot.contacts.firstOrNull { it.id == target.contactId && !it.blocked } ?: return
        val selected = target.blockId?.takeIf { blockId -> snapshot.blocksByContact[contact.id].orEmpty().any { it.id == blockId } }
        reconnectLiveContactId = null
        mutate {
            copy(
                activeContactId = contact.id,
                route = AppRoute.Canvas,
                canvasMode = CanvasMode.Offline,
                selectedBlockId = selected,
                contacts = contacts.updateContact(contact.id) { it.copy(status = ConnectionStatus.Offline) },
            )
        }
    }

    fun openExport(contactId: String) {
        reconnectLiveContactId = null
        mutate {
            copy(
                activeContactId = contactId,
                route = AppRoute.Canvas,
                canvasMode = CanvasMode.Offline,
                selectedBlockId = null,
                requestedExportContactId = contactId,
                contacts = contacts.updateContact(contactId) { it.copy(status = ConnectionStatus.Offline) },
            )
        }
    }

    fun consumeExportRequest() = transient { copy(requestedExportContactId = null) }

    fun endSession() {
        val activeId = state.value.activeContactId
        reconnectLiveContactId = null
        mutate {
            copy(
                route = AppRoute.Contacts,
                activeContactId = null,
                selectedBlockId = null,
                hiddenInputBlockId = null,
                contacts = if (activeId == null) contacts else contacts.updateContact(activeId) {
                    it.copy(status = ConnectionStatus.Available)
                },
            )
        }
    }

    fun selectBlock(blockId: String?) = transient { copy(selectedBlockId = blockId) }

    fun createBlock(
        x: Float = 62f,
        y: Float = 180f,
        visibleBounds: CanvasBounds? = null,
    ): SharedTextBlock? {
        if (activeSpaceIsReadOnly()) return null
        val contactId = state.value.activeContactId ?: return null
        val position = findAvailableBlockPosition(x, y, 230f, state.value.activeBlocks, visibleBounds)
        val block = SharedTextBlock(UUID.randomUUID().toString(), "", position.x, position.y, width = 230f)
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { it + block },
                textDocuments = textDocuments + (block.id to emptyList()),
                selectedBlockId = block.id,
            ).appendEvent(contactId, CanvasEventType.BlockCreated, block.id, blockPayload(block))
        }
        return block
    }

    fun updateBlockText(blockId: String, value: String) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        val block = state.value.activeBlocks.firstOrNull { it.id == blockId } ?: return
        val atoms = state.value.textDocuments[blockId]
            ?: initialTextAtoms(blockId, block.text)
        val update = applyLocalTextChange(atoms, value, identity.deviceId)
        if (update.delta.isEmpty) return
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                    blocks.map { if (it.id == blockId) it.copy(text = renderTextAtoms(update.atoms)) else it }
                },
                textDocuments = textDocuments + (blockId to update.atoms),
            )
        }
        val existing = pendingTextDeltas[blockId] ?: TextCrdtDelta(emptyList(), emptyList())
        pendingTextDeltas[blockId] = TextCrdtDelta(
            inserts = existing.inserts + update.delta.inserts,
            deletes = existing.deletes + update.delta.deletes,
        )
        scheduleTextEvent(contactId, blockId)
    }

    fun appendText(blockId: String, value: String) {
        val block = state.value.activeBlocks.firstOrNull { it.id == blockId } ?: return
        updateBlockText(blockId, block.text + value)
    }

    fun moveBlock(blockId: String, dx: Float, dy: Float) = updateBlock(blockId) {
        if (it.pinned) it else it.copy(x = it.x + dx, y = it.y + dy)
    }

    fun commitBlockMove(blockId: String, dx: Float, dy: Float) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        val source = state.value.activeBlocks.firstOrNull { it.id == blockId } ?: return
        if (source.pinned) return
        val proposedX = source.x + dx
        val proposedY = source.y + dy
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                    blocks.map { block ->
                        if (block.id == source.id) block.copy(x = proposedX, y = proposedY, clusterId = null) else block
                    }
                },
                selectedBlockId = blockId,
                notice = null,
            ).appendEvent(
                contactId,
                CanvasEventType.BlockMoved,
                blockId,
                blockPayload(source.copy(x = proposedX, y = proposedY, clusterId = null)),
            )
        }
    }

    fun addHandDrawnFrame(points: List<CanvasPoint>) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        if (points.size < 3) return
        val simplified = points.filterIndexed { index, _ -> index % 2 == 0 }.take(600)
        if (!isUsefulFrame(simplified)) return
        val frame = HandDrawnFrame(UUID.randomUUID().toString(), simplified)
        mutate {
            copy(framesByContact = framesByContact.updateFrames(contactId) { it + frame })
                .appendEvent(contactId, CanvasEventType.FrameCreated, frame.id, framePayload(frame))
        }
    }

    fun deleteHandDrawnFrame(frameId: String) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        mutate {
            copy(framesByContact = framesByContact.updateFrames(contactId) { frames ->
                frames.filterNot { it.id == frameId }
            }).appendEvent(contactId, CanvasEventType.FrameDeleted, frameId, "{}")
        }
    }

    fun resizeBlockFromCorner(blockId: String, corner: ResizeCorner, delta: androidx.compose.ui.geometry.Offset) = updateBlock(blockId) {
        val fromLeft = corner == ResizeCorner.TopLeft || corner == ResizeCorner.BottomLeft
        val fromTop = corner == ResizeCorner.TopLeft || corner == ResizeCorner.TopRight
        val nextWidth = (it.width + if (fromLeft) -delta.x else delta.x).coerceIn(150f, 360f)
        val currentHeight = estimatedBlockHeight(it)
        val contentHeightAtNewWidth = estimatedBlockHeight(it.copy(width = nextWidth, height = null))
        val requestedHeight = currentHeight + if (fromTop) -delta.y else delta.y
        val nextHeight = requestedHeight.coerceIn(contentHeightAtNewWidth, 520f)
        it.copy(
            x = if (fromLeft) it.x + (it.width - nextWidth) else it.x,
            y = if (fromTop) it.y + (currentHeight - nextHeight) else it.y,
            width = nextWidth,
            height = nextHeight,
        )
    }

    fun ungroupBlock(blockId: String) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        val source = state.value.activeBlocks.firstOrNull { it.id == blockId } ?: return
        val clusterId = source.clusterId ?: return
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                    val remainingIds = blocks.filter { it.clusterId == clusterId && it.id != blockId }.map { it.id }
                    blocks.map { block ->
                        when {
                            block.id == blockId -> block.copy(clusterId = null)
                            remainingIds.size == 1 && block.id in remainingIds -> block.copy(clusterId = null)
                            else -> block
                        }
                    }
                },
                notice = "BLOCK UNGROUPED",
            )
        }
    }

    fun togglePin(blockId: String) = updateBlock(blockId) { it.copy(pinned = !it.pinned) }

    fun deleteBlock(blockId: String) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        val block = state.value.activeBlocks.firstOrNull { it.id == blockId } ?: return
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                    blocks.filterNot { it.id == blockId }
                },
                selectedBlockId = null,
                lastDeleted = DeletedBlock(contactId, block),
                notice = "BLOCK DELETED — UNDO",
            ).appendEvent(contactId, CanvasEventType.BlockDeleted, blockId, blockPayload(block))
        }
    }

    fun undoDelete() {
        if (activeSpaceIsReadOnly()) return
        val deleted = state.value.lastDeleted ?: return
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(deleted.contactId) { it + deleted.block },
                lastDeleted = null,
                notice = "BLOCK RESTORED",
            ).appendEvent(deleted.contactId, CanvasEventType.BlockRestored, deleted.block.id, blockPayload(deleted.block))
        }
    }

    fun mergeBlocks(sourceId: String, targetId: String) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        val source = state.value.activeBlocks.firstOrNull { it.id == sourceId } ?: return
        val target = state.value.activeBlocks.firstOrNull { it.id == targetId } ?: return
        if (sourceId == targetId || state.value.canvasMode == CanvasMode.Offline) return
        val merged = mergeSharedBlocks(source, target)
        mutate {
            copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                    blocks.filterNot { it.id == sourceId || it.id == targetId } + merged
                },
                textDocuments = (textDocuments - sourceId) + (merged.id to initialTextAtoms(merged.id, merged.text)),
                selectedBlockId = merged.id,
                notice = "BLOCKS MERGED",
            ).appendEvent(
                contactId,
                CanvasEventType.BlockMerged,
                merged.id,
                JSONObject().put("sourceId", sourceId).put("target", JSONObject(blockPayload(merged))).toString(),
            )
        }
    }

    fun beginHiddenInput(blockId: String) {
        if (activeSpaceIsReadOnly()) return
        transient { copy(hiddenInputBlockId = blockId, selectedBlockId = blockId) }
    }

    fun updateHiddenDraft(value: String) {
        val blockId = state.value.hiddenInputBlockId ?: return
        mutate { copy(hiddenDrafts = hiddenDrafts + (blockId to value)) }
    }

    fun keepHiddenDraft() = transient { copy(hiddenInputBlockId = null, notice = "DRAFT SAVED LOCALLY") }

    fun discardHiddenDraft() {
        val blockId = state.value.hiddenInputBlockId ?: return
        mutate {
            copy(
                hiddenDrafts = hiddenDrafts - blockId,
                hiddenInputBlockId = null,
                notice = "DRAFT DISCARDED",
            )
        }
    }

    fun revealHiddenDraft() {
        val blockId = state.value.hiddenInputBlockId ?: return
        val draft = state.value.hiddenDrafts[blockId].orEmpty()
        if (draft.isNotBlank()) {
            val block = state.value.activeBlocks.firstOrNull { it.id == blockId }
            if (block != null) updateBlockText(blockId, listOf(block.text, draft).filter { it.isNotBlank() }.joinToString(" "))
        }
        mutate {
            copy(
                hiddenDrafts = hiddenDrafts - blockId,
                hiddenInputBlockId = null,
                notice = "HIDDEN INPUT REVEALED",
            )
        }
    }

    fun setTheme(theme: CanvasTheme) = mutate { copy(theme = theme) }

    fun setLanguage(language: UiLanguage) = mutate { copy(language = language) }

    fun setTextSize(textSize: UiTextSize) = mutate { copy(textSize = textSize) }

    fun setHighContrast(enabled: Boolean) = mutate {
        copy(highContrast = enabled, notice = if (enabled) "HIGH CONTRAST ENABLED" else "HIGH CONTRAST DISABLED")
    }

    fun setReduceMotion(enabled: Boolean) = mutate {
        copy(reduceMotion = enabled, notice = if (enabled) "REDUCED MOTION ENABLED" else "REDUCED MOTION DISABLED")
    }

    fun setConnectionRequestTimeout(seconds: Int) {
        if (seconds !in setOf(15, 30, 60)) return
        mutate { copy(connectionRequestTimeoutSeconds = seconds, notice = "CONNECTION TIMEOUT UPDATED") }
    }

    fun setContactSortMode(mode: ContactSortMode) = mutate {
        copy(contactSortMode = mode, notice = "CONTACT ORDER UPDATED")
    }

    fun removeAllLocalContent() {
        connectJob?.cancel()
        clearRequestJob?.cancel()
        clearUndoJob?.cancel()
        eventJobs.values.forEach { it.cancel() }
        eventJobs.clear()
        pendingTextDeltas.clear()
        reconnectLiveContactId = null
        relay.close()
        mutate {
            copy(
                route = AppRoute.Contacts,
                contacts = emptyList(),
                blocksByContact = emptyMap(),
                framesByContact = emptyMap(),
                eventsByContact = emptyMap(),
                textDocuments = emptyMap(),
                pendingInvites = emptyList(),
                activeContactId = null,
                selectedBlockId = null,
                hiddenDrafts = emptyMap(),
                hiddenInputBlockId = null,
                incomingConnectionContactId = null,
                requestedExportContactId = null,
                viewportOffset = androidx.compose.ui.geometry.Offset.Zero,
                viewportScale = 1f,
                lastDeleted = null,
                pendingClearRequest = null,
                clearUndo = null,
                lastSuccessfulSyncEpochMs = 0L,
                notice = "LOCAL CONTACTS AND CANVASES REMOVED",
            )
        }
    }

    fun setAppLockEnabled(enabled: Boolean) = mutate {
        copy(appLockEnabled = enabled, notice = if (enabled) "APP LOCK ENABLED" else "APP LOCK DISABLED")
    }

    fun setBlockScreenshots(enabled: Boolean) = mutate {
        copy(blockScreenshots = enabled, notice = if (enabled) "SCREENSHOTS BLOCKED" else "SCREENSHOTS ALLOWED")
    }

    fun setGenericNotificationsEnabled(enabled: Boolean) = mutate {
        copy(genericNotificationsEnabled = enabled, notice = if (enabled) "GENERIC NOTIFICATIONS ENABLED" else "NOTIFICATIONS DISABLED")
    }

    fun setServerUrl(value: String) {
        val normalized = value.trim()
        if (!isAllowedServerUrl(normalized)) {
            transient { copy(notice = if (BuildConfig.DEBUG) "INVALID SERVER ADDRESS" else "WSS REQUIRED") }
            return
        }
        mutate { copy(serverUrl = normalized, notice = "SERVER ADDRESS UPDATED") }
        scheduleRelaySync(0)
    }

    fun migrateServer(value: String) {
        val normalized = value.trim()
        val snapshot = state.value
        if (!isAllowedServerUrl(normalized)) {
            transient { copy(notice = if (BuildConfig.DEBUG) "INVALID SERVER ADDRESS" else "WSS REQUIRED") }
            return
        }
        if (normalized == snapshot.serverUrl) {
            transient { copy(notice = "THIS SERVER IS ALREADY ACTIVE") }
            return
        }
        if (snapshot.eventsByContact.values.flatten().any { it.pendingSync }) {
            transient { copy(notice = "WAIT FOR CURRENT SYNC BEFORE MIGRATING") }
            return
        }
        transient { copy(notice = "TESTING NEW SERVER") }
        viewModelScope.launch(Dispatchers.IO) {
            val compatible = testServerProtocol(normalized)
            if (!compatible) {
                transient { copy(notice = "NEW SERVER FAILED PROTOCOL TEST") }
                return@launch
            }
            mutate {
                copy(
                    previousServerUrl = serverUrl,
                    serverUrl = normalized,
                    eventsByContact = eventsByContact.mapValues { (_, events) -> events.map { it.copy(pendingSync = true) } },
                    contacts = contacts.map { it.copy(status = ConnectionStatus.Offline) },
                    canvasMode = if (route == AppRoute.Canvas) CanvasMode.Offline else canvasMode,
                    notice = "SERVER MIGRATION STARTED",
                )
            }
            persistImmediately()
            scheduleRelaySync(0)
        }
    }

    fun rollbackServerMigration() {
        val previous = state.value.previousServerUrl ?: run {
            transient { copy(notice = "NO SERVER ROLLBACK AVAILABLE") }
            return
        }
        mutate {
            val current = serverUrl
            copy(
                serverUrl = previous,
                previousServerUrl = current,
                eventsByContact = eventsByContact.mapValues { (_, events) -> events.map { it.copy(pendingSync = true) } },
                contacts = contacts.map { it.copy(status = ConnectionStatus.Offline) },
                canvasMode = if (route == AppRoute.Canvas) CanvasMode.Offline else canvasMode,
                notice = "SERVER ROLLBACK STARTED",
            )
        }
        persistImmediately()
        scheduleRelaySync(0)
    }

    fun setViewport(offsetX: Float, offsetY: Float, scale: Float) = transient {
        copy(
            viewportOffset = androidx.compose.ui.geometry.Offset(offsetX, offsetY),
            viewportScale = scale.coerceIn(.25f, 3f),
        )
    }

    fun resetViewport() = transient { copy(viewportOffset = androidx.compose.ui.geometry.Offset.Zero, viewportScale = 1f) }

    fun dismissNotice() = transient { copy(notice = null) }

    fun showNotice(message: String) = transient { copy(notice = message) }

    fun requestClearForBoth() {
        val snapshot = state.value
        val contact = snapshot.activeContact ?: return
        if (contact.readOnly || contact.blocked || snapshot.canvasMode != CanvasMode.Live || contact.status != ConnectionStatus.Connected) {
            transient { copy(notice = "BOTH PEOPLE MUST BE LIVE TO CLEAR") }
            return
        }
        flushPendingTextOperations()
        val requestId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + 60_000L
        mutate {
            copy(
                pendingClearRequest = PendingClearRequest(contact.id, requestId, expiresAt, initiatedLocally = true),
                notice = "CLEAR REQUEST SENT",
            ).appendEvent(
                contact.id,
                CanvasEventType.ClearRequested,
                requestId,
                JSONObject().put("expiresAt", expiresAt).toString(),
            )
        }
        persistImmediately()
        scheduleClearRequestExpiry(requestId, expiresAt)
    }

    fun approveClearRequest() {
        val request = state.value.pendingClearRequest ?: return
        if (request.initiatedLocally || request.expiresAtEpochMs <= System.currentTimeMillis()) return
        mutate {
            copy(pendingClearRequest = null, notice = "CLEAR APPROVED — WAITING FOR BOTH DEVICES")
                .appendEvent(request.contactId, CanvasEventType.ClearApproved, request.requestId, "{}")
        }
        persistImmediately()
    }

    fun rejectClearRequest() {
        val request = state.value.pendingClearRequest ?: return
        if (!request.initiatedLocally) {
            state.value.contacts.firstOrNull { it.id == request.contactId }?.let { contact ->
                publishControl(contact, "clear_reject", JSONObject().put("requestId", request.requestId))
            }
        }
        clearRequestJob?.cancel()
        mutate { copy(pendingClearRequest = null, notice = "CLEAR REQUEST REJECTED") }
        persistImmediately()
    }

    fun undoClearForBoth() {
        val undo = state.value.clearUndo ?: return
        if (undo.expiresAtEpochMs <= System.currentTimeMillis()) {
            transient { copy(clearUndo = null, notice = "UNDO WINDOW ENDED") }
            return
        }
        clearUndoJob?.cancel()
        mutate {
            restoreClearSnapshot(undo)
                .appendEvent(undo.contactId, CanvasEventType.ClearUndone, undo.requestId, "{}")
                .copy(clearUndo = null, notice = "SPACE RESTORED")
        }
        persistImmediately()
    }

    fun createEncryptedArchive(password: String): Result<ByteArray> = runCatching {
        val snapshot = state.value
        val contact = snapshot.activeContact ?: error("No active contact")
        SpaceExporter.encryptedArchive(
            contact,
            snapshot.activeBlocks,
            snapshot.activeFrames,
            snapshot.eventsByContact[contact.id].orEmpty(),
            password.toCharArray(),
        )
    }

    fun createPdfExport(includeTimestamps: Boolean = false): Result<ByteArray> = runCatching {
        val snapshot = state.value
        val contact = snapshot.activeContact ?: error("No active contact")
        SpaceExporter.pdf(
            contact,
            snapshot.activeBlocks,
            snapshot.activeFrames,
            snapshot.eventsByContact[contact.id].orEmpty(),
            includeTimestamps,
        )
    }

    fun createImageExport(): Result<ByteArray> = runCatching {
        val snapshot = state.value
        val contact = snapshot.activeContact ?: error("No active contact")
        SpaceExporter.png(contact, snapshot.activeBlocks, snapshot.activeFrames)
    }

    fun recordSuccessfulExport(format: String) {
        val contactId = state.value.activeContactId ?: return
        mutate {
        appendEvent(
            contactId,
            CanvasEventType.ExportCreated,
            UUID.randomUUID().toString(),
            JSONObject().put("format", format).put("createdAt", System.currentTimeMillis()).toString(),
        )
        }
    }

    fun restoreEncryptedArchive(bytes: ByteArray, password: String): Boolean {
        val restored = runCatching { SpaceExporter.decryptArchive(bytes, password.toCharArray()) }
            .onFailure { transient { copy(notice = "ARCHIVE COULD NOT BE UNLOCKED") } }
            .getOrNull() ?: return false
        val contactId = UUID.randomUUID().toString()
        mutate {
            copy(
                contacts = contacts + TrustedContact(
                    id = contactId,
                    displayName = "Recovered · ${restored.originalName}",
                    verified = false,
                    status = ConnectionStatus.Offline,
                    spaceId = "",
                    readOnly = true,
                ),
                blocksByContact = blocksByContact + (contactId to restored.blocks),
                framesByContact = framesByContact + (contactId to restored.frames),
                eventsByContact = eventsByContact + (contactId to emptyList()),
                textDocuments = textDocuments + restored.blocks.associate { block -> block.id to initialTextAtoms(block.id, block.text) },
                notice = "ARCHIVE RESTORED LOCALLY",
            )
        }
        return true
    }

    fun previewEncryptedArchive(bytes: ByteArray, password: String): ArchivePreview? =
        runCatching { SpaceExporter.decryptArchive(bytes, password.toCharArray()) }
            .onFailure { transient { copy(notice = "ARCHIVE COULD NOT BE UNLOCKED") } }
            .getOrNull()
            ?.let { restored ->
                ArchivePreview(
                    originalName = restored.originalName,
                    blockCount = restored.blocks.size,
                    frameCount = restored.frames.size,
                )
            }

    fun copyRecoveredBlockToContact(blockId: String, targetContactId: String) {
        val snapshot = state.value
        val sourceContact = snapshot.activeContact ?: return
        if (!sourceContact.readOnly) return
        val target = snapshot.contacts.firstOrNull {
            it.id == targetContactId && !it.readOnly && !it.blocked
        } ?: return
        val source = snapshot.activeBlocks.firstOrNull { it.id == blockId } ?: return
        val position = findAvailableBlockPosition(
            source.x + 24f,
            source.y + 24f,
            source.width,
            snapshot.blocksByContact[target.id].orEmpty(),
        )
        val copied = source.copy(
            id = UUID.randomUUID().toString(),
            x = position.x,
            y = position.y,
            isRecovered = false,
            pinned = false,
        )
        reconnectLiveContactId = null
        mutate {
            copy(
                activeContactId = target.id,
                route = AppRoute.Canvas,
                canvasMode = CanvasMode.Offline,
                selectedBlockId = copied.id,
                blocksByContact = blocksByContact.updateSpace(target.id) { it + copied },
                textDocuments = textDocuments + (copied.id to initialTextAtoms(copied.id, copied.text)),
                notice = "BLOCK COPIED TO PRIVATE SPACE",
            ).appendEvent(target.id, CanvasEventType.BlockCreated, copied.id, blockPayload(copied))
        }
    }

    private fun activeSpaceIsReadOnly(): Boolean = state.value.activeContact?.readOnly == true

    private fun updateBlock(blockId: String, transform: (SharedTextBlock) -> SharedTextBlock) {
        if (activeSpaceIsReadOnly()) return
        val contactId = state.value.activeContactId ?: return
        val before = state.value.activeBlocks.firstOrNull { it.id == blockId } ?: return
        val after = transform(before)
        val eventType = when {
            before.width != after.width || before.height != after.height -> CanvasEventType.BlockResized
            before.pinned != after.pinned -> CanvasEventType.BlockPinned
            else -> null
        }
        mutate {
            val updated = copy(
                blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                    blocks.map { if (it.id == blockId) after else it }
                },
            )
            if (eventType == CanvasEventType.BlockPinned) {
                updated.appendEvent(contactId, eventType, blockId, blockPayload(after))
            } else {
                updated
            }
        }
        if (eventType != null && eventType != CanvasEventType.BlockPinned) {
            scheduleBlockEvent(contactId, blockId, eventType)
        }
    }

    private fun scheduleBlockEvent(contactId: String, blockId: String, type: CanvasEventType) {
        val key = "$blockId:${type.name}"
        eventJobs.remove(key)?.cancel()
        eventJobs[key] = viewModelScope.launch {
            delay(220)
            val latest = state.value.blocksByContact[contactId].orEmpty().firstOrNull { it.id == blockId }
                ?: return@launch
            mutate { appendEvent(contactId, type, blockId, blockPayload(latest)) }
            eventJobs.remove(key)
        }
    }

    private fun scheduleTextEvent(contactId: String, blockId: String) {
        val key = "$blockId:${CanvasEventType.TextOperation.name}"
        eventJobs.remove(key)?.cancel()
        eventJobs[key] = viewModelScope.launch {
            delay(450)
            flushPendingTextEvent(contactId, blockId)
            eventJobs.remove(key)
        }
    }

    private fun flushPendingTextEvent(contactId: String, blockId: String) {
        val delta = pendingTextDeltas.remove(blockId) ?: return
        if (delta.isEmpty) return
        // Keep each encrypted relay envelope well below the relay's 1 MiB frame limit.
        delta.inserts.chunked(1_500).forEach { inserts ->
            mutate {
                appendEvent(
                    contactId,
                    CanvasEventType.TextOperation,
                    blockId,
                    textDeltaPayload(TextCrdtDelta(inserts, emptyList())),
                )
            }
        }
        delta.deletes.distinct().chunked(1_500).forEach { deletes ->
            mutate {
                appendEvent(
                    contactId,
                    CanvasEventType.TextOperation,
                    blockId,
                    textDeltaPayload(TextCrdtDelta(emptyList(), deletes)),
                )
            }
        }
    }

    fun flushPendingTextOperations() {
        pendingTextDeltas.keys.toList().forEach { blockId ->
            val key = "$blockId:${CanvasEventType.TextOperation.name}"
            eventJobs.remove(key)?.cancel()
            val contactId = state.value.blocksByContact.entries
                .firstOrNull { (_, blocks) -> blocks.any { it.id == blockId } }
                ?.key
            if (contactId != null) flushPendingTextEvent(contactId, blockId)
        }
        store.save(_state.value)
        scheduleRelaySync(0)
    }

    private fun textDeltaPayload(delta: TextCrdtDelta): String = JSONObject().apply {
        put("inserts", org.json.JSONArray().apply {
            delta.inserts.forEach { atom ->
                put(JSONObject().apply {
                    put("id", atom.id)
                    put("afterId", atom.afterId)
                    put("value", atom.value)
                })
            }
        })
        put("deletes", org.json.JSONArray().apply { delta.deletes.distinct().forEach(::put) })
    }.toString()

    private fun parseTextDelta(payload: String): TextCrdtDelta? = runCatching {
        val root = JSONObject(payload)
        val insertsJson = root.optJSONArray("inserts") ?: org.json.JSONArray()
        val deletesJson = root.optJSONArray("deletes") ?: org.json.JSONArray()
        require(insertsJson.length() <= 100_000 && deletesJson.length() <= 100_000)
        val inserts = buildList {
            repeat(insertsJson.length()) { index ->
                val item = insertsJson.getJSONObject(index)
                val value = item.getString("value")
                if (value.codePointCount(0, value.length) == 1) {
                    add(TextAtom(item.getString("id"), item.optString("afterId").takeIf { it.isNotBlank() && it != "null" }, value))
                }
            }
        }
        val deletes = buildList { repeat(deletesJson.length()) { index -> add(deletesJson.getString(index)) } }
        TextCrdtDelta(inserts, deletes)
    }.getOrNull()

    private fun PrivateCanvasState.appendEvent(
        contactId: String,
        type: CanvasEventType,
        objectId: String,
        payload: String,
    ): PrivateCanvasState {
        val existing = eventsByContact[contactId].orEmpty()
        val nextSequence = (existing.maxOfOrNull { it.sequence } ?: 0L) + 1L
        val event = CanvasEvent(
            id = UUID.randomUUID().toString(),
            contactId = contactId,
            deviceId = identity.deviceId,
            sequence = nextSequence,
            type = type,
            objectId = objectId,
            payload = payload,
            occurredAtEpochMs = System.currentTimeMillis(),
        )
        return copy(eventsByContact = eventsByContact.updateEvents(contactId) { (it + event).takeLast(10_000) })
    }

    private fun blockPayload(block: SharedTextBlock): String = JSONObject().apply {
        put("id", block.id)
        put("text", block.text)
        put("x", block.x.toDouble())
        put("y", block.y.toDouble())
        put("width", block.width.toDouble())
        put("height", block.height?.toDouble())
        put("pinned", block.pinned)
        put("recovered", block.isRecovered)
    }.toString()

    private fun framePayload(frame: HandDrawnFrame): String = JSONObject().apply {
        put("id", frame.id)
        put("points", org.json.JSONArray().apply {
            frame.points.forEach { point -> put(org.json.JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) }
        })
    }.toString()

    private fun contactPayload(contact: TrustedContact): String = JSONObject().apply {
        put("contactId", contact.id)
        put("remoteDeviceId", contact.remoteDeviceId)
        put("spaceId", contact.spaceId)
        put("identityPublicKey", contact.identityPublicKey)
        put("pairedAt", contact.pairedAtEpochMs)
    }.toString()

    private fun mutate(transform: PrivateCanvasState.() -> PrivateCanvasState) {
        _state.update(transform)
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(180)
            store.save(_state.value)
        }
        scheduleRelaySync(280)
    }

    private fun scheduleRelaySync(delayMs: Long) {
        relaySyncJob?.cancel()
        relaySyncJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val snapshot = state.value
            val spaces = buildList {
                snapshot.contacts.filter { !it.blocked && it.spaceId.isNotBlank() && it.spaceKeyBase64.isNotBlank() }.forEach {
                    add(RelaySpace(it.spaceId, it.spaceKeyBase64))
                }
                snapshot.pendingInvites.filter { it.expiresAtEpochMs > System.currentTimeMillis() }.forEach {
                    add(RelaySpace(it.spaceId, it.spaceKeyBase64))
                }
            }.distinctBy { it.spaceId }
            relay.connect(snapshot.serverUrl, identity.deviceId, spaces)
            publishPending(snapshot)
        }
    }

    private fun publishPending(snapshot: PrivateCanvasState = state.value) {
        snapshot.contacts.forEach { contact ->
            if (contact.blocked) return@forEach
            val space = contact.relaySpace() ?: return@forEach
            val helloId = "pair:${contact.spaceId}:${identity.deviceId}"
            relay.publish(space, helloId, "pair_accept", JSONObject().apply {
                put("deviceId", identity.deviceId)
                put("displayName", snapshot.localDisplayName)
                put("publicKey", identity.publicKeyBase64)
                put("pairedAt", System.currentTimeMillis())
            }.toString())
            snapshot.eventsByContact[contact.id].orEmpty().filter { it.pendingSync }.forEach { event ->
                relay.publish(space, event.id, "event", eventJson(event).toString())
            }
        }
    }

    private fun publishControl(contact: TrustedContact, kind: String, body: JSONObject) {
        val space = contact.relaySpace() ?: return
        relay.publish(space, UUID.randomUUID().toString(), kind, body.toString())
    }

    private fun handleRelayIncoming(incoming: RelayIncoming) {
        viewModelScope.launch {
            when (incoming) {
                RelayIncoming.Connected -> {
                    transient { copy(notice = null) }
                    publishPending()
                }
                RelayIncoming.Disconnected -> {
                    markRelayOffline(null)
                    scheduleRelaySync(3_000)
                }
                is RelayIncoming.Failure -> {
                    markRelayOffline(incoming.message)
                    scheduleRelaySync(4_000)
                }
                is RelayIncoming.Ack -> mutate {
                    copy(
                        eventsByContact = eventsByContact.mapValues { (_, events) ->
                            events.map { if (it.id == incoming.id) it.copy(pendingSync = false) else it }
                        },
                        lastSuccessfulSyncEpochMs = System.currentTimeMillis(),
                    )
                }
                is RelayIncoming.Presence -> {
                    val contact = state.value.contacts.firstOrNull { it.spaceId == incoming.spaceId } ?: return@launch
                    val isActiveCanvas = state.value.route == AppRoute.Canvas && state.value.activeContactId == contact.id
                    when {
                        incoming.count >= 2 && reconnectLiveContactId == contact.id -> {
                            transient {
                                copy(
                                    contacts = contacts.updateContact(contact.id) { it.copy(status = ConnectionStatus.Connected) },
                                    canvasMode = if (isActiveCanvas) CanvasMode.Live else canvasMode,
                                    notice = null,
                                )
                            }
                        }
                        incoming.count < 2 && isActiveCanvas && contact.status == ConnectionStatus.Connected -> {
                            reconnectLiveContactId = contact.id
                            transient {
                                copy(
                                    contacts = contacts.updateContact(contact.id) { it.copy(status = ConnectionStatus.Offline) },
                                    canvasMode = CanvasMode.Offline,
                                )
                            }
                        }
                        contact.status != ConnectionStatus.Connected && contact.status != ConnectionStatus.Requesting -> {
                        transient {
                            copy(contacts = contacts.updateContact(contact.id) {
                                it.copy(status = if (incoming.count >= 2) ConnectionStatus.Available else ConnectionStatus.Offline)
                            })
                        }
                    }
                    }
                }
                is RelayIncoming.Item -> handleRelayItem(incoming)
            }
        }
    }

    private fun markRelayOffline(message: String?) {
        val before = state.value
        val activeId = before.activeContactId
        if (before.route == AppRoute.Canvas && before.canvasMode == CanvasMode.Live && activeId != null) {
            reconnectLiveContactId = activeId
        }
        transient {
            copy(
                contacts = contacts.map { contact ->
                    if (contact.id == activeId &&
                        (contact.status == ConnectionStatus.Connected || contact.status == ConnectionStatus.Requesting)
                    ) contact.copy(status = ConnectionStatus.Offline) else contact
                },
                canvasMode = if (route == AppRoute.Canvas) CanvasMode.Offline else canvasMode,
                notice = message,
            )
        }
    }

    private fun handleRelayItem(item: RelayIncoming.Item) {
        when (item.kind) {
            "pair_accept" -> acceptRemotePair(item)
            "event" -> applyRemoteEvent(item)
            "connect_request" -> {
                val remoteDevice = runCatching { JSONObject(item.plaintext).getString("fromDeviceId") }.getOrNull()
                val contact = state.value.contacts.firstOrNull { it.spaceId == item.spaceId && it.remoteDeviceId == remoteDevice }
                    ?: return
                if (contact.blocked) return
                transient { copy(incomingConnectionContactId = contact.id, notice = null) }
                showGenericNotification(contact, connectionRequest = true, blockId = null)
            }
            "connect_accept" -> {
                val contact = state.value.contacts.firstOrNull { it.spaceId == item.spaceId } ?: return
                connectJob?.cancel()
                reconnectLiveContactId = contact.id
                mutate {
                    copy(
                        contacts = contacts.updateContact(contact.id) { it.copy(status = ConnectionStatus.Connected) },
                        activeContactId = contact.id,
                        route = AppRoute.Canvas,
                        canvasMode = CanvasMode.Live,
                        selectedBlockId = null,
                        notice = null,
                    )
                }
            }
            "connect_decline" -> {
                val contact = state.value.contacts.firstOrNull { it.spaceId == item.spaceId } ?: return
                connectJob?.cancel()
                mutate { copy(contacts = contacts.updateContact(contact.id) { it.copy(status = ConnectionStatus.Available) }, notice = "REQUEST DECLINED") }
            }
            "clear_reject" -> {
                val requestId = runCatching { JSONObject(item.plaintext).getString("requestId") }.getOrNull() ?: return
                val pending = state.value.pendingClearRequest
                if (pending?.initiatedLocally == true && pending.requestId == requestId) {
                    clearRequestJob?.cancel()
                    mutate { copy(pendingClearRequest = null, notice = "CLEAR REQUEST REJECTED") }
                }
            }
        }
    }

    private fun acceptRemotePair(item: RelayIncoming.Item) {
        val root = runCatching { JSONObject(item.plaintext) }.getOrNull() ?: return
        val remoteDeviceId = root.optString("deviceId")
        if (remoteDeviceId.isBlank() || remoteDeviceId == identity.deviceId) return
        if (state.value.contacts.any { it.remoteDeviceId == remoteDeviceId || it.spaceId == item.spaceId }) return
        val pending = state.value.pendingInvites.firstOrNull { it.spaceId == item.spaceId } ?: return
        val remotePublicKey = root.optString("publicKey")
        if (remotePublicKey.isBlank()) return
        val contactId = UUID.randomUUID().toString()
        val contact = TrustedContact(
            id = contactId,
            displayName = normalizeDisplayName(root.optString("displayName")),
            verified = false,
            remoteDeviceId = remoteDeviceId,
            identityPublicKey = remotePublicKey,
            spaceId = item.spaceId,
            safetyCode = PairingInviteCodec.safetyCode(identity.publicKeyBase64, remotePublicKey, item.spaceId),
            pairedAtEpochMs = root.optLong("pairedAt", System.currentTimeMillis()),
            spaceKeyBase64 = pending.spaceKeyBase64,
        )
        mutate {
            copy(
                contacts = contacts + contact,
                blocksByContact = blocksByContact + (contactId to emptyList()),
                framesByContact = framesByContact + (contactId to emptyList()),
                eventsByContact = eventsByContact + (contactId to emptyList()),
                pendingInvites = pendingInvites.filterNot { it.spaceId == item.spaceId },
                notice = "CONTACT PAIRED — VERIFY SAFETY CODE",
            ).appendEvent(contactId, CanvasEventType.ContactPaired, contactId, contactPayload(contact))
        }
    }

    private fun applyRemoteEvent(item: RelayIncoming.Item) {
        val contact = state.value.contacts.firstOrNull { it.spaceId == item.spaceId && !it.blocked } ?: return
        val root = runCatching { JSONObject(item.plaintext) }.getOrNull() ?: return
        val eventId = root.optString("id")
        if (eventId.isBlank() || state.value.eventsByContact[contact.id].orEmpty().any { it.id == eventId }) return
        val type = runCatching { CanvasEventType.valueOf(root.getString("type")) }.getOrNull() ?: return
        val event = CanvasEvent(
            id = eventId,
            contactId = contact.id,
            deviceId = root.optString("deviceId"),
            sequence = root.optLong("sequence"),
            type = type,
            objectId = root.optString("objectId"),
            payload = root.optString("payload"),
            occurredAtEpochMs = root.optLong("occurredAt"),
            pendingSync = false,
        )
        val pendingClear = state.value.pendingClearRequest
        if (event.type == CanvasEventType.SpaceCleared ||
            (event.type == CanvasEventType.ClearApproved && pendingClear?.initiatedLocally == true && pendingClear.requestId == event.objectId)
        ) {
            cancelPendingSpaceEdits(contact.id)
        }
        if (event.type == CanvasEventType.BlockDeleted) {
            val local = state.value.blocksByContact[contact.id].orEmpty().firstOrNull { it.id == event.objectId }
            if (hasRemoteDeleteConflict(local, parseBlock(event.payload))) {
                pendingTextDeltas.remove(event.objectId)
                eventJobs.remove("$event.objectId:${CanvasEventType.TextOperation.name}")?.cancel()
            }
        }
        mutate {
            val withEvent = copy(eventsByContact = eventsByContact.updateEvents(contact.id) { it + event })
            withEvent.applyRemoteMutation(contact.id, event)
        }
        if (event.type in setOf(
                CanvasEventType.ClearRequested,
                CanvasEventType.ClearApproved,
                CanvasEventType.SpaceCleared,
                CanvasEventType.ClearUndone,
            )
        ) persistImmediately()
        when (event.type) {
            CanvasEventType.ClearRequested -> state.value.pendingClearRequest?.let {
                scheduleClearRequestExpiry(it.requestId, it.expiresAtEpochMs)
            }
            CanvasEventType.ClearApproved, CanvasEventType.SpaceCleared -> state.value.clearUndo?.let {
                scheduleClearUndoExpiry(it.requestId, it.expiresAtEpochMs)
            }
            else -> Unit
        }
        if (event.deviceId != identity.deviceId && event.type in setOf(
                CanvasEventType.BlockCreated,
                CanvasEventType.BlockDeleted,
                CanvasEventType.FrameCreated,
                CanvasEventType.FrameDeleted,
                CanvasEventType.SpaceCleared,
                CanvasEventType.ClearUndone,
            )
        ) {
            showGenericNotification(
                contact,
                connectionRequest = false,
                blockId = event.objectId.takeIf { event.type == CanvasEventType.BlockCreated },
            )
        }
    }

    private fun PrivateCanvasState.applyRemoteMutation(contactId: String, event: CanvasEvent): PrivateCanvasState {
        val remoteBlock = if (event.type.name.startsWith("Block")) parseBlock(event.payload) else null
        return when (event.type) {
            CanvasEventType.BlockCreated, CanvasEventType.BlockRestored -> if (remoteBlock == null) this else {
                val atoms = textDocuments[remoteBlock.id] ?: initialTextAtoms(remoteBlock.id, remoteBlock.text)
                copy(
                    blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                        if (blocks.any { it.id == remoteBlock.id }) blocks else blocks + remoteBlock.copy(text = renderTextAtoms(atoms))
                    },
                    textDocuments = textDocuments + (remoteBlock.id to atoms),
                )
            }
            CanvasEventType.TextOperation -> {
                val block = blocksByContact[contactId].orEmpty().firstOrNull { it.id == event.objectId } ?: return this
                val currentAtoms = textDocuments[block.id] ?: initialTextAtoms(block.id, block.text)
                val delta = parseTextDelta(event.payload) ?: return this
                val nextAtoms = applyRemoteTextDelta(currentAtoms, delta)
                copy(
                    blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                        blocks.map { if (it.id == block.id) it.copy(text = renderTextAtoms(nextAtoms)) else it }
                    },
                    textDocuments = textDocuments + (block.id to nextAtoms),
                )
            }
            CanvasEventType.BlockTextChanged, CanvasEventType.BlockMoved, CanvasEventType.BlockResized, CanvasEventType.BlockPinned -> {
                if (remoteBlock == null) this else {
                    val nextDocuments = if (event.type == CanvasEventType.BlockTextChanged) {
                        textDocuments + (remoteBlock.id to initialTextAtoms(remoteBlock.id, remoteBlock.text))
                    } else textDocuments
                    copy(
                        blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                            blocks.map { if (it.id == remoteBlock.id) remoteBlock else it }
                        },
                        textDocuments = nextDocuments,
                    )
                }
            }
            CanvasEventType.BlockDeleted -> {
                val local = blocksByContact[contactId].orEmpty().firstOrNull { it.id == event.objectId }
                if (hasRemoteDeleteConflict(local, remoteBlock)) {
                    val recovered = local!!.copy(
                        id = UUID.randomUUID().toString(),
                        x = local.x + 24f,
                        y = local.y + 24f,
                        isRecovered = true,
                    )
                    val draft = hiddenDrafts[local.id]
                    copy(
                        blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                            blocks.filterNot { it.id == local.id } + recovered
                        },
                        textDocuments = (textDocuments - local.id) +
                            (recovered.id to initialTextAtoms(recovered.id, recovered.text)),
                        hiddenDrafts = (hiddenDrafts - local.id).let { drafts ->
                            if (draft == null) drafts else drafts + (recovered.id to draft)
                        },
                        selectedBlockId = if (selectedBlockId == local.id) recovered.id else selectedBlockId,
                        notice = "CONFLICT RECOVERED",
                    ).appendEvent(
                        contactId,
                        CanvasEventType.BlockRestored,
                        recovered.id,
                        blockPayload(recovered),
                    )
                } else {
                    copy(
                        blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                            blocks.filterNot { it.id == event.objectId }
                        },
                        textDocuments = textDocuments - event.objectId,
                        hiddenDrafts = hiddenDrafts - event.objectId,
                        selectedBlockId = selectedBlockId.takeUnless { it == event.objectId },
                    )
                }
            }
            CanvasEventType.BlockMerged -> runCatching {
                val root = JSONObject(event.payload)
                val sourceId = root.getString("sourceId")
                val target = parseBlock(root.getJSONObject("target").toString()) ?: return@runCatching this
                copy(
                    blocksByContact = blocksByContact.updateSpace(contactId) { blocks ->
                        blocks.filterNot { it.id == sourceId || it.id == target.id } + target
                    },
                    textDocuments = (textDocuments - sourceId) + (target.id to initialTextAtoms(target.id, target.text)),
                )
            }.getOrDefault(this)
            CanvasEventType.FrameCreated -> parseFrame(event.payload)?.let { frame ->
                copy(framesByContact = framesByContact.updateFrames(contactId) { frames -> if (frames.any { it.id == frame.id }) frames else frames + frame })
            } ?: this
            CanvasEventType.FrameDeleted -> copy(framesByContact = framesByContact.updateFrames(contactId) { frames -> frames.filterNot { it.id == event.objectId } })
            CanvasEventType.ClearRequested -> {
                val expiresAt = runCatching { JSONObject(event.payload).getLong("expiresAt") }.getOrDefault(0L)
                if (event.deviceId == identity.deviceId || expiresAt <= System.currentTimeMillis()) this
                else copy(
                    pendingClearRequest = PendingClearRequest(contactId, event.objectId, expiresAt, initiatedLocally = false),
                    notice = null,
                )
            }
            CanvasEventType.ClearApproved -> {
                val pending = pendingClearRequest
                if (pending?.initiatedLocally == true && pending.requestId == event.objectId && pending.expiresAtEpochMs > System.currentTimeMillis()) {
                    clearSpace(contactId, event.objectId, appendClearEvent = true)
                } else this
            }
            CanvasEventType.SpaceCleared -> clearSpace(contactId, event.objectId, appendClearEvent = false)
            CanvasEventType.ClearUndone -> {
                val undo = clearUndo
                if (undo != null && undo.contactId == contactId && undo.requestId == event.objectId) {
                    restoreClearSnapshot(undo).copy(clearUndo = null, notice = "SPACE RESTORED")
                } else this
            }
            else -> this
        }
    }

    private fun PrivateCanvasState.clearSpace(
        contactId: String,
        requestId: String,
        appendClearEvent: Boolean,
    ): PrivateCanvasState {
        val oldBlocks = blocksByContact[contactId].orEmpty()
        val oldFrames = framesByContact[contactId].orEmpty()
        val oldBlockIds = oldBlocks.mapTo(mutableSetOf()) { it.id }
        val undo = ClearUndoSnapshot(
            contactId = contactId,
            requestId = requestId,
            blocks = oldBlocks,
            frames = oldFrames,
            expiresAtEpochMs = System.currentTimeMillis() + 30_000L,
        )
        val cleared = copy(
            blocksByContact = blocksByContact + (contactId to emptyList()),
            framesByContact = framesByContact + (contactId to emptyList()),
            textDocuments = textDocuments.filterKeys { it !in oldBlockIds },
            hiddenDrafts = hiddenDrafts.filterKeys { it !in oldBlockIds },
            selectedBlockId = selectedBlockId.takeUnless { it in oldBlockIds },
            pendingClearRequest = null,
            clearUndo = undo,
            notice = "SPACE CLEARED — UNDO AVAILABLE",
        )
        return if (appendClearEvent) {
            cleared.appendEvent(contactId, CanvasEventType.SpaceCleared, requestId, "{}")
        } else cleared
    }

    private fun PrivateCanvasState.restoreClearSnapshot(undo: ClearUndoSnapshot): PrivateCanvasState {
        val restoredDocuments = undo.blocks.associate { block -> block.id to initialTextAtoms(block.id, block.text) }
        return copy(
            blocksByContact = blocksByContact + (undo.contactId to undo.blocks),
            framesByContact = framesByContact + (undo.contactId to undo.frames),
            textDocuments = textDocuments + restoredDocuments,
        )
    }

    private fun cancelPendingSpaceEdits(contactId: String) {
        state.value.blocksByContact[contactId].orEmpty().forEach { block ->
            pendingTextDeltas.remove(block.id)
            eventJobs.keys.filter { it.startsWith("${block.id}:") }.forEach { key -> eventJobs.remove(key)?.cancel() }
        }
    }

    private fun scheduleClearRequestExpiry(requestId: String, expiresAt: Long) {
        clearRequestJob?.cancel()
        clearRequestJob = viewModelScope.launch {
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            mutate {
                if (pendingClearRequest?.requestId == requestId) {
                    copy(pendingClearRequest = null, notice = "CLEAR REQUEST EXPIRED")
                } else this
            }
        }
    }

    private fun scheduleClearUndoExpiry(requestId: String, expiresAt: Long) {
        clearUndoJob?.cancel()
        clearUndoJob = viewModelScope.launch {
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            mutate { if (clearUndo?.requestId == requestId) copy(clearUndo = null) else this }
        }
    }

    private fun showGenericNotification(contact: TrustedContact, connectionRequest: Boolean, blockId: String?) {
        val snapshot = state.value
        if (appInForeground || !snapshot.genericNotificationsEnabled) return
        val application = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(application, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!connectionRequest && blockId != null) {
                putExtra(MainActivity.EXTRA_CONTACT_ID, contact.id)
                putExtra(MainActivity.EXTRA_BLOCK_ID, blockId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            application,
            (contact.id + blockId.orEmpty()).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (connectionRequest) "Incoming connection request" else "New activity in ${contact.displayName}"
        val notification = NotificationCompat.Builder(application, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText("Open ONaline to view it")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        application.getSystemService(NotificationManager::class.java).notify(contact.id.hashCode(), notification)
    }

    private fun persistImmediately() {
        persistJob?.cancel()
        store.save(state.value)
    }

    private fun isAllowedServerUrl(value: String): Boolean =
        value.startsWith("wss://", ignoreCase = true) ||
            (BuildConfig.DEBUG && value.startsWith("ws://", ignoreCase = true))

    private fun testServerProtocol(serverUrl: String): Boolean = runCatching {
        val uri = URI(serverUrl)
        val healthScheme = if (uri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        val healthUrl = URI(healthScheme, uri.userInfo, uri.host, uri.port, "/health", null, null).toString()
        val request = Request.Builder().url(healthUrl).get().build()
        migrationHttp.newCall(request).execute().use { response ->
            response.isSuccessful && response.body?.string()?.let { body ->
                JSONObject(body).optBoolean("ok") && JSONObject(body).optInt("protocol") == 1
            } == true
        }
    }.getOrDefault(false)

    private fun eventJson(event: CanvasEvent): JSONObject = JSONObject().apply {
        put("id", event.id); put("deviceId", event.deviceId); put("sequence", event.sequence); put("type", event.type.name)
        put("objectId", event.objectId); put("payload", event.payload); put("occurredAt", event.occurredAtEpochMs)
    }

    private fun parseBlock(payload: String): SharedTextBlock? = runCatching {
        val root = JSONObject(payload)
        SharedTextBlock(
            id = root.getString("id"), text = root.optString("text"), x = root.getDouble("x").toFloat(), y = root.getDouble("y").toFloat(),
            width = root.optDouble("width", 210.0).toFloat(), height = root.optDouble("height", Double.NaN).toFloat().takeIf { it.isFinite() },
            pinned = root.optBoolean("pinned"), isRecovered = root.optBoolean("recovered"),
        )
    }.getOrNull()

    private fun parseFrame(payload: String): HandDrawnFrame? = runCatching {
        val root = JSONObject(payload)
        val pointsJson = root.getJSONArray("points")
        val points = buildList { repeat(pointsJson.length()) { index -> pointsJson.getJSONArray(index).let { add(CanvasPoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())) } } }
        HandDrawnFrame(root.getString("id"), points)
    }.getOrNull()

    private companion object {
        const val NOTIFICATION_CHANNEL = "private_canvas_activity"
    }

    private fun TrustedContact.relaySpace(): RelaySpace? = if (spaceId.isBlank() || spaceKeyBase64.isBlank()) null else RelaySpace(spaceId, spaceKeyBase64)

    private fun transient(transform: PrivateCanvasState.() -> PrivateCanvasState) {
        _state.update(transform)
    }

    private fun List<TrustedContact>.updateContact(
        contactId: String,
        transform: (TrustedContact) -> TrustedContact,
    ) = map { if (it.id == contactId) transform(it) else it }

    private fun Map<String, List<SharedTextBlock>>.updateSpace(
        contactId: String,
        transform: (List<SharedTextBlock>) -> List<SharedTextBlock>,
    ) = this + (contactId to transform(this[contactId].orEmpty()))

    private fun Map<String, List<HandDrawnFrame>>.updateFrames(
        contactId: String,
        transform: (List<HandDrawnFrame>) -> List<HandDrawnFrame>,
    ) = this + (contactId to transform(this[contactId].orEmpty()))

    private fun Map<String, List<CanvasEvent>>.updateEvents(
        contactId: String,
        transform: (List<CanvasEvent>) -> List<CanvasEvent>,
    ) = this + (contactId to transform(this[contactId].orEmpty()))

    private fun starterBlocks() = listOf(
        SharedTextBlock(UUID.randomUUID().toString(), "What should we do tonight? ✨", 34f, 125f, 200f),
        SharedTextBlock(UUID.randomUUID().toString(), "Walk by the river 🌿", 166f, 300f, 174f),
        SharedTextBlock(UUID.randomUUID().toString(), "after dinner 🍲", 48f, 430f, 150f),
    )

    override fun onCleared() {
        flushPendingTextOperations()
        eventJobs.values.forEach { it.cancel() }
        persistJob?.cancel()
        relaySyncJob?.cancel()
        connectJob?.cancel()
        store.save(_state.value)
        relay.close()
        super.onCleared()
    }
}
