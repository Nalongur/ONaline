package space.privatecanvas.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Prototype persistence encrypted with a non-exportable Android Keystore key.
 * This protects local-at-rest data only; it is intentionally not presented as E2EE.
 */
class SecureLocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("private_canvas_local", Context.MODE_PRIVATE)

    fun load(): PrivateCanvasState? {
        val blob = preferences.getString(PAYLOAD, null) ?: return null
        val iv = preferences.getString(IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val json = String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8)
            decode(JSONObject(json))
        }.getOrNull()
    }

    fun save(state: PrivateCanvasState) {
        val stableState = state.copy(
            route = AppRoute.Contacts,
            activeContactId = null,
            selectedBlockId = null,
            hiddenInputBlockId = null,
            notice = null,
            lastDeleted = null,
            incomingConnectionContactId = null,
            requestedExportContactId = null,
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(encode(stableState).toString().toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString(PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(state: PrivateCanvasState): JSONObject = JSONObject().apply {
        put("theme", state.theme.name)
        put("language", state.language.name)
        put("textSize", state.textSize.name)
        put("highContrast", state.highContrast)
        put("reduceMotion", state.reduceMotion)
        put("connectionRequestTimeoutSeconds", state.connectionRequestTimeoutSeconds)
        put("contactSortMode", state.contactSortMode.name)
        put("localDisplayName", state.localDisplayName)
        put("appLockEnabled", state.appLockEnabled)
        put("blockScreenshots", state.blockScreenshots)
        put("genericNotifications", state.genericNotificationsEnabled)
        put("serverUrl", state.serverUrl)
        put("previousServerUrl", state.previousServerUrl)
        put("lastSuccessfulSync", state.lastSuccessfulSyncEpochMs)
        put("contacts", JSONArray().apply {
            state.contacts.forEach { contact ->
                put(JSONObject().apply {
                    put("id", contact.id)
                    put("name", contact.displayName)
                    put("verified", contact.verified)
                    put("remoteDeviceId", contact.remoteDeviceId)
                    put("identityPublicKey", contact.identityPublicKey)
                    put("spaceId", contact.spaceId)
                    put("safetyCode", contact.safetyCode)
                    put("pairedAt", contact.pairedAtEpochMs)
                    put("spaceKey", contact.spaceKeyBase64)
                    put("blocked", contact.blocked)
                    put("readOnly", contact.readOnly)
                    put("pinned", contact.pinned)
                })
            }
        })
        put("spaces", JSONObject().apply {
            state.blocksByContact.forEach { (contactId, blocks) ->
                put(contactId, JSONArray().apply {
                    blocks.forEach { block ->
                        put(JSONObject().apply {
                            put("id", block.id)
                            put("text", block.text)
                            put("x", block.x.toDouble())
                            put("y", block.y.toDouble())
                            put("width", block.width.toDouble())
                            put("height", block.height?.toDouble())
                            put("pinned", block.pinned)
                            put("recovered", block.isRecovered)
                            put("cluster", block.clusterId)
                        })
                    }
                })
            }
        })
        put("frames", JSONObject().apply {
            state.framesByContact.forEach { (contactId, frames) ->
                put(contactId, JSONArray().apply {
                    frames.forEach { frame ->
                        put(JSONObject().apply {
                            put("id", frame.id)
                            put("points", JSONArray().apply {
                                frame.points.forEach { point ->
                                    put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
                                }
                            })
                        })
                    }
                })
            }
        })
        put("drafts", JSONObject().apply {
            state.hiddenDrafts.forEach { (blockId, draft) -> put(blockId, draft) }
        })
        put("events", JSONObject().apply {
            state.eventsByContact.forEach { (contactId, events) ->
                put(contactId, JSONArray().apply {
                    events.forEach { event ->
                        put(JSONObject().apply {
                            put("id", event.id)
                            put("deviceId", event.deviceId)
                            put("sequence", event.sequence)
                            put("type", event.type.name)
                            put("objectId", event.objectId)
                            put("payload", event.payload)
                            put("occurredAt", event.occurredAtEpochMs)
                            put("pendingSync", event.pendingSync)
                        })
                    }
                })
            }
        })
        put("textDocuments", JSONObject().apply {
            state.textDocuments.forEach { (blockId, atoms) ->
                put(blockId, JSONArray().apply {
                    atoms.forEach { atom ->
                        put(JSONObject().apply {
                            put("id", atom.id)
                            put("afterId", atom.afterId)
                            put("value", atom.value)
                            put("deleted", atom.deleted)
                        })
                    }
                })
            }
        })
        put("pendingInvites", JSONArray().apply {
            state.pendingInvites.filter { it.expiresAtEpochMs > System.currentTimeMillis() }.forEach { invite ->
                put(JSONObject().apply {
                    put("spaceId", invite.spaceId)
                    put("spaceKey", invite.spaceKeyBase64)
                    put("serverUrl", invite.serverUrl)
                    put("expiresAt", invite.expiresAtEpochMs)
                })
            }
        })
        state.pendingClearRequest
            ?.takeIf { it.expiresAtEpochMs > System.currentTimeMillis() }
            ?.let { request ->
                put("pendingClear", JSONObject().apply {
                    put("contactId", request.contactId)
                    put("requestId", request.requestId)
                    put("expiresAt", request.expiresAtEpochMs)
                    put("initiatedLocally", request.initiatedLocally)
                })
            }
        state.clearUndo
            ?.takeIf { it.expiresAtEpochMs > System.currentTimeMillis() }
            ?.let { undo ->
                put("clearUndo", JSONObject().apply {
                    put("contactId", undo.contactId)
                    put("requestId", undo.requestId)
                    put("expiresAt", undo.expiresAtEpochMs)
                    put("blocks", JSONArray().apply {
                        undo.blocks.forEach { block ->
                            put(JSONObject().apply {
                                put("id", block.id); put("text", block.text); put("x", block.x.toDouble()); put("y", block.y.toDouble())
                                put("width", block.width.toDouble()); put("height", block.height?.toDouble()); put("pinned", block.pinned)
                                put("recovered", block.isRecovered)
                            })
                        }
                    })
                    put("frames", JSONArray().apply {
                        undo.frames.forEach { frame ->
                            put(JSONObject().apply {
                                put("id", frame.id)
                                put("points", JSONArray().apply {
                                    frame.points.forEach { point -> put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) }
                                })
                            })
                        }
                    })
                })
            }
    }

    private fun decode(root: JSONObject): PrivateCanvasState {
        val contactsArray = root.optJSONArray("contacts") ?: JSONArray()
        val contacts = buildList {
            repeat(contactsArray.length()) { index ->
                val item = contactsArray.getJSONObject(index)
                add(
                    TrustedContact(
                        id = item.getString("id"),
                        displayName = normalizeDisplayName(item.getString("name")),
                        verified = item.optBoolean("verified", false),
                        remoteDeviceId = item.optString("remoteDeviceId"),
                        identityPublicKey = item.optString("identityPublicKey"),
                        spaceId = item.optString("spaceId"),
                        safetyCode = item.optString("safetyCode"),
                        pairedAtEpochMs = item.optLong("pairedAt"),
                        spaceKeyBase64 = item.optString("spaceKey"),
                        blocked = item.optBoolean("blocked"),
                        readOnly = item.optBoolean("readOnly"),
                        pinned = item.optBoolean("pinned"),
                    ),
                )
            }
        }
        val spacesJson = root.optJSONObject("spaces") ?: JSONObject()
        val spaces = contacts.associate { contact ->
            val blocksArray = spacesJson.optJSONArray(contact.id) ?: JSONArray()
            contact.id to buildList {
                repeat(blocksArray.length()) { index ->
                    val item = blocksArray.getJSONObject(index)
                    add(
                        SharedTextBlock(
                            id = item.getString("id"),
                            text = item.optString("text"),
                            x = item.optDouble("x", 40.0).toFloat(),
                            y = item.optDouble("y", 140.0).toFloat(),
                            width = item.optDouble("width", 210.0).toFloat(),
                            height = item.optDouble("height", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }?.toFloat(),
                            pinned = item.optBoolean("pinned"),
                            isRecovered = item.optBoolean("recovered"),
                            clusterId = item.optString("cluster").takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
            }
        }
        val draftsJson = root.optJSONObject("drafts") ?: JSONObject()
        val drafts = buildMap {
            draftsJson.keys().forEach { key -> put(key, draftsJson.optString(key)) }
        }
        val theme = runCatching {
            CanvasTheme.valueOf(root.optString("theme", CanvasTheme.MistSage.name))
        }.getOrDefault(CanvasTheme.MistSage)
        val language = runCatching {
            UiLanguage.valueOf(root.optString("language", UiLanguage.English.name))
        }.getOrDefault(UiLanguage.English)
        val textSize = runCatching {
            UiTextSize.valueOf(root.optString("textSize", UiTextSize.FollowSystem.name))
        }.getOrDefault(UiTextSize.FollowSystem)
        val contactSortMode = runCatching {
            ContactSortMode.valueOf(root.optString("contactSortMode", ContactSortMode.PinnedFirst.name))
        }.getOrDefault(ContactSortMode.PinnedFirst)
        val framesJson = root.optJSONObject("frames") ?: JSONObject()
        val frames = contacts.associate { contact ->
            val frameArray = framesJson.optJSONArray(contact.id) ?: JSONArray()
            contact.id to buildList {
                repeat(frameArray.length()) { frameIndex ->
                    val frameJson = frameArray.getJSONObject(frameIndex)
                    val pointsJson = frameJson.optJSONArray("points") ?: JSONArray()
                    val points = buildList {
                        repeat(pointsJson.length()) { pointIndex ->
                            val point = pointsJson.getJSONArray(pointIndex)
                            add(CanvasPoint(point.optDouble(0).toFloat(), point.optDouble(1).toFloat()))
                        }
                    }
                    if (isUsefulFrame(points)) add(HandDrawnFrame(frameJson.optString("id"), points))
                }
            }
        }
        val eventsJson = root.optJSONObject("events") ?: JSONObject()
        val events = contacts.associate { contact ->
            val eventArray = eventsJson.optJSONArray(contact.id) ?: JSONArray()
            contact.id to buildList {
                repeat(eventArray.length()) { eventIndex ->
                    val item = eventArray.getJSONObject(eventIndex)
                    val type = runCatching { CanvasEventType.valueOf(item.getString("type")) }.getOrNull()
                    if (type != null) {
                        add(
                            CanvasEvent(
                                id = item.getString("id"),
                                contactId = contact.id,
                                deviceId = item.optString("deviceId"),
                                sequence = item.optLong("sequence"),
                                type = type,
                                objectId = item.optString("objectId"),
                                payload = item.optString("payload"),
                                occurredAtEpochMs = item.optLong("occurredAt"),
                                pendingSync = item.optBoolean("pendingSync", true),
                            ),
                        )
                    }
                }
            }
        }
        val pendingJson = root.optJSONArray("pendingInvites") ?: JSONArray()
        val pendingInvites = buildList {
            repeat(pendingJson.length()) { index ->
                val item = pendingJson.getJSONObject(index)
                val invite = PendingInvite(
                    spaceId = item.optString("spaceId"),
                    spaceKeyBase64 = item.optString("spaceKey"),
                    serverUrl = item.optString("serverUrl"),
                    expiresAtEpochMs = item.optLong("expiresAt"),
                )
                if (invite.spaceId.isNotBlank() && invite.spaceKeyBase64.isNotBlank() && invite.expiresAtEpochMs > System.currentTimeMillis()) add(invite)
            }
        }
        val pendingClear = root.optJSONObject("pendingClear")?.let { item ->
            PendingClearRequest(
                contactId = item.optString("contactId"),
                requestId = item.optString("requestId"),
                expiresAtEpochMs = item.optLong("expiresAt"),
                initiatedLocally = item.optBoolean("initiatedLocally"),
            ).takeIf { request ->
                request.contactId in contacts.map { it.id } &&
                    request.requestId.isNotBlank() && request.expiresAtEpochMs > System.currentTimeMillis()
            }
        }
        val clearUndo = root.optJSONObject("clearUndo")?.let { item ->
            val blocksJson = item.optJSONArray("blocks") ?: JSONArray()
            val undoBlocks = buildList {
                repeat(blocksJson.length().coerceAtMost(5_000)) { index ->
                    val block = blocksJson.getJSONObject(index)
                    val id = block.optString("id")
                    if (id.isNotBlank()) {
                        add(
                            SharedTextBlock(
                                id = id,
                                text = block.optString("text").take(100_000),
                                x = block.optDouble("x", 40.0).toFloat(),
                                y = block.optDouble("y", 140.0).toFloat(),
                                width = block.optDouble("width", 210.0).toFloat().coerceIn(150f, 360f),
                                height = block.optDouble("height", Double.NaN).takeIf { it.isFinite() }?.toFloat()?.coerceIn(96f, 520f),
                                pinned = block.optBoolean("pinned"),
                                isRecovered = block.optBoolean("recovered"),
                            ),
                        )
                    }
                }
            }
            val framesArray = item.optJSONArray("frames") ?: JSONArray()
            val undoFrames = buildList {
                repeat(framesArray.length().coerceAtMost(2_000)) { frameIndex ->
                    val frame = framesArray.getJSONObject(frameIndex)
                    val pointsJson = frame.optJSONArray("points") ?: JSONArray()
                    val points = buildList {
                        repeat(pointsJson.length().coerceAtMost(2_000)) { pointIndex ->
                            val point = pointsJson.getJSONArray(pointIndex)
                            val x = point.optDouble(0).toFloat()
                            val y = point.optDouble(1).toFloat()
                            if (x.isFinite() && y.isFinite()) add(CanvasPoint(x, y))
                        }
                    }
                    if (isUsefulFrame(points)) add(HandDrawnFrame(frame.optString("id"), points))
                }
            }
            ClearUndoSnapshot(
                contactId = item.optString("contactId"),
                requestId = item.optString("requestId"),
                blocks = undoBlocks,
                frames = undoFrames,
                expiresAtEpochMs = item.optLong("expiresAt"),
            ).takeIf { undo ->
                undo.contactId in contacts.map { it.id } &&
                    undo.requestId.isNotBlank() && undo.expiresAtEpochMs > System.currentTimeMillis()
            }
        }
        val textDocumentsJson = root.optJSONObject("textDocuments") ?: JSONObject()
        val textDocuments = buildMap {
            textDocumentsJson.keys().forEach { blockId ->
                val atomsJson = textDocumentsJson.optJSONArray(blockId) ?: JSONArray()
                val atoms = buildList {
                    repeat(atomsJson.length().coerceAtMost(500_000)) { index ->
                        val item = atomsJson.getJSONObject(index)
                        val id = item.optString("id")
                        val value = item.optString("value")
                        if (id.isNotBlank() && value.isNotEmpty() && value.codePointCount(0, value.length) == 1) {
                            add(
                                TextAtom(
                                    id = id,
                                    afterId = item.optString("afterId").takeIf { it.isNotBlank() && it != "null" },
                                    value = value,
                                    deleted = item.optBoolean("deleted"),
                                ),
                            )
                        }
                    }
                }
                put(blockId, atoms)
            }
        }
        return PrivateCanvasState(
            localDisplayName = normalizeDisplayName(root.optString("localDisplayName", "You")),
            contacts = contacts,
            blocksByContact = spaces,
            framesByContact = frames,
            eventsByContact = events,
            hiddenDrafts = drafts,
            theme = theme,
            language = language,
            textSize = textSize,
            highContrast = root.optBoolean("highContrast", false),
            reduceMotion = root.optBoolean("reduceMotion", false),
            connectionRequestTimeoutSeconds = root.optInt("connectionRequestTimeoutSeconds", 15).takeIf { it in setOf(15, 30, 60) } ?: 15,
            contactSortMode = contactSortMode,
            appLockEnabled = root.optBoolean("appLockEnabled", false),
            blockScreenshots = root.optBoolean("blockScreenshots", true),
            genericNotificationsEnabled = root.optBoolean("genericNotifications", false),
            serverUrl = root.optString("serverUrl", "ws://10.0.2.2:9876/v1/ws"),
            previousServerUrl = root.optString("previousServerUrl").takeIf { it.isNotBlank() && it != "null" },
            lastSuccessfulSyncEpochMs = root.optLong("lastSuccessfulSync"),
            pendingInvites = pendingInvites,
            textDocuments = textDocuments,
            pendingClearRequest = pendingClear,
            clearUndo = clearUndo,
        )
    }

    private companion object {
        const val KEY_ALIAS = "private_canvas_local_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PAYLOAD = "encrypted_payload"
        const val IV = "encrypted_iv"
    }
}
