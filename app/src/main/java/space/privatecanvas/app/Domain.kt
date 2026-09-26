package space.privatecanvas.app

import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

enum class AppRoute { Contacts, Canvas }

enum class ConnectionStatus(val label: String) {
    Available("AVAILABLE"),
    Requesting("REQUESTING"),
    Connected("CONNECTED"),
    Offline("OFFLINE"),
    Busy("BUSY"),
}

enum class CanvasMode { Live, Offline }

enum class ResizeCorner { TopLeft, TopRight, BottomLeft, BottomRight }

enum class UiLanguage { English, Chinese }

fun UiLanguage.text(english: String, chinese: String): String = if (this == UiLanguage.Chinese) chinese else english

enum class ContactSortMode {
    PinnedFirst,
    Name,
    RecentlyPaired,
}

enum class UiTextSize(val label: String, val fontScale: Float) {
    FollowSystem("FOLLOW SYSTEM", 0f),
    Compact("COMPACT", .9f),
    Comfortable("COMFORTABLE", 1f),
    Large("LARGE", 1.18f),
}

enum class CanvasTheme(
    val label: String,
    val background: Long,
    val accent: Long,
) {
    MistSage("MIST SAGE", 0xFFF5F3ED, 0xFF939B7E),
    MoonBlue("MOON BLUE", 0xFFF0F4F5, 0xFF839BA8),
    DuskViolet("DUSK VIOLET", 0xFFF4F1F5, 0xFF978EA1),
    ApricotMist("APRICOT MIST", 0xFFF8F1EC, 0xFFB89A86),
}

data class TrustedContact(
    val id: String,
    val displayName: String,
    val verified: Boolean = true,
    val status: ConnectionStatus = ConnectionStatus.Available,
    val remoteDeviceId: String = "",
    val identityPublicKey: String = "",
    val spaceId: String = "",
    val safetyCode: String = "",
    val pairedAtEpochMs: Long = 0L,
    val spaceKeyBase64: String = "",
    val blocked: Boolean = false,
    val readOnly: Boolean = false,
    val pinned: Boolean = false,
) {
    val initials: String
        get() = displayName.trim().split(Regex("\\s+")).take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifBlank { "?" }
}

fun orderedContacts(
    contacts: List<TrustedContact>,
    mode: ContactSortMode = ContactSortMode.PinnedFirst,
): List<TrustedContact> = when (mode) {
    ContactSortMode.PinnedFirst -> contacts.filter { it.pinned } + contacts.filterNot { it.pinned }
    ContactSortMode.Name -> contacts.sortedWith(
        compareByDescending<TrustedContact> { it.pinned }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.trim() },
    )
    ContactSortMode.RecentlyPaired -> contacts.sortedWith(
        compareByDescending<TrustedContact> { it.pinned }.thenByDescending { it.pairedAtEpochMs },
    )
}

enum class CanvasEventType {
    ContactPaired,
    ContactRemoved,
    BlockCreated,
    BlockTextChanged,
    TextOperation,
    BlockMoved,
    BlockResized,
    BlockPinned,
    BlockMerged,
    BlockDeleted,
    BlockRestored,
    FrameCreated,
    FrameDeleted,
    ExportCreated,
    ClearRequested,
    ClearApproved,
    SpaceCleared,
    ClearUndone,
}

data class CanvasEvent(
    val id: String,
    val contactId: String,
    val deviceId: String,
    val sequence: Long,
    val type: CanvasEventType,
    val objectId: String,
    val payload: String,
    val occurredAtEpochMs: Long,
    val pendingSync: Boolean = true,
)

data class PendingInvite(
    val spaceId: String,
    val spaceKeyBase64: String,
    val serverUrl: String,
    val expiresAtEpochMs: Long,
)

data class ArchivePreview(
    val originalName: String,
    val blockCount: Int,
    val frameCount: Int,
)

data class PendingClearRequest(
    val contactId: String,
    val requestId: String,
    val expiresAtEpochMs: Long,
    val initiatedLocally: Boolean,
)

data class ClearUndoSnapshot(
    val contactId: String,
    val requestId: String,
    val blocks: List<SharedTextBlock>,
    val frames: List<HandDrawnFrame>,
    val expiresAtEpochMs: Long,
)

data class DiagnosticsSnapshot(
    val versionName: String,
    val contactCount: Int,
    val writableSpaceCount: Int,
    val pendingEncryptedEventCount: Int,
    val lastSuccessfulSyncEpochMs: Long,
    val serverUrl: String,
    val approximateLocalDataBytes: Long,
    val blockCount: Int,
    val frameCount: Int,
    val draftCount: Int,
    val eventCount: Int,
)

data class NotificationTarget(
    val contactId: String,
    val blockId: String? = null,
    val nonce: Long = System.nanoTime(),
)

fun PrivateCanvasState.diagnostics(): DiagnosticsSnapshot = DiagnosticsSnapshot(
    versionName = BuildConfig.VERSION_NAME,
    contactCount = contacts.size,
    writableSpaceCount = contacts.count { !it.readOnly && !it.blocked },
    pendingEncryptedEventCount = eventsByContact.values.sumOf { events -> events.count { it.pendingSync } },
    lastSuccessfulSyncEpochMs = lastSuccessfulSyncEpochMs,
    serverUrl = serverUrl,
    approximateLocalDataBytes = contacts.sumOf {
        (it.displayName + it.id + it.spaceId + it.safetyCode).toByteArray().size.toLong()
    } + blocksByContact.values.flatten().sumOf { it.text.toByteArray().size.toLong() + 64L } +
        eventsByContact.values.flatten().sumOf { it.payload.toByteArray().size.toLong() + 96L } +
        hiddenDrafts.values.sumOf { it.toByteArray().size.toLong() } +
        framesByContact.values.flatten().sumOf { it.points.size * 8L + 32L },
    blockCount = blocksByContact.values.sumOf { it.size },
    frameCount = framesByContact.values.sumOf { it.size },
    draftCount = hiddenDrafts.size,
    eventCount = eventsByContact.values.sumOf { it.size },
)

fun normalizeDisplayName(raw: String): String = raw
    .filterNot { character ->
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
    }
    .trim()
    .ifBlank { "Avery" }

data class SharedTextBlock(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float = 210f,
    val height: Float? = null,
    val pinned: Boolean = false,
    val isRecovered: Boolean = false,
    val clusterId: String? = null,
)

data class CanvasPoint(val x: Float, val y: Float)

data class HandDrawnFrame(
    val id: String,
    val points: List<CanvasPoint>,
)

fun isUsefulFrame(points: List<CanvasPoint>): Boolean {
    if (points.size < 3) return false
    if (points.maxOf { it.x } - points.minOf { it.x } < 24f) return false
    if (points.maxOf { it.y } - points.minOf { it.y } < 24f) return false
    val doubledArea = points.indices.sumOf { index ->
        val current = points[index]
        val next = points[(index + 1) % points.size]
        (current.x * next.y - next.x * current.y).toDouble()
    }
    return abs(doubledArea) >= 800.0
}

fun HandDrawnFrame.containsOrTouches(point: CanvasPoint, tolerance: Float = 18f): Boolean {
    if (points.size < 3) return false
    var inside = false
    var previous = points.last()
    points.forEach { current ->
        val crossesRay = (current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x - current.x) * (point.y - current.y) /
            ((previous.y - current.y).takeIf { it != 0f } ?: .0001f) + current.x
        if (crossesRay) inside = !inside
        if (distanceToSegment(point, previous, current) <= tolerance) return true
        previous = current
    }
    return inside
}

private fun distanceToSegment(point: CanvasPoint, start: CanvasPoint, end: CanvasPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    if (dx == 0f && dy == 0f) return hypot(point.x - start.x, point.y - start.y)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
}

data class DeletedBlock(
    val contactId: String,
    val block: SharedTextBlock,
)

data class PrivateCanvasState(
    val route: AppRoute = AppRoute.Contacts,
    val localDisplayName: String = "You",
    val contacts: List<TrustedContact> = emptyList(),
    val blocksByContact: Map<String, List<SharedTextBlock>> = emptyMap(),
    val framesByContact: Map<String, List<HandDrawnFrame>> = emptyMap(),
    val eventsByContact: Map<String, List<CanvasEvent>> = emptyMap(),
    val textDocuments: Map<String, List<TextAtom>> = emptyMap(),
    val pendingInvites: List<PendingInvite> = emptyList(),
    val activeContactId: String? = null,
    val canvasMode: CanvasMode = CanvasMode.Offline,
    val selectedBlockId: String? = null,
    val hiddenDrafts: Map<String, String> = emptyMap(),
    val hiddenInputBlockId: String? = null,
    val theme: CanvasTheme = CanvasTheme.MistSage,
    val language: UiLanguage = UiLanguage.English,
    val textSize: UiTextSize = UiTextSize.FollowSystem,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val connectionRequestTimeoutSeconds: Int = 15,
    val contactSortMode: ContactSortMode = ContactSortMode.PinnedFirst,
    val appLockEnabled: Boolean = false,
    val blockScreenshots: Boolean = true,
    val genericNotificationsEnabled: Boolean = false,
    val serverUrl: String = "ws://10.0.2.2:9876/v1/ws",
    val previousServerUrl: String? = null,
    val lastSuccessfulSyncEpochMs: Long = 0L,
    val incomingConnectionContactId: String? = null,
    val requestedExportContactId: String? = null,
    val viewportOffset: Offset = Offset.Zero,
    val viewportScale: Float = 1f,
    val lastDeleted: DeletedBlock? = null,
    val pendingClearRequest: PendingClearRequest? = null,
    val clearUndo: ClearUndoSnapshot? = null,
    val notice: String? = null,
) {
    val activeContact: TrustedContact?
        get() = contacts.firstOrNull { it.id == activeContactId }

    val activeBlocks: List<SharedTextBlock>
        get() = blocksByContact[activeContactId].orEmpty()

    val activeFrames: List<HandDrawnFrame>
        get() = framesByContact[activeContactId].orEmpty()
}

fun mergeSharedBlocks(source: SharedTextBlock, target: SharedTextBlock): SharedTextBlock = target.copy(
    text = listOf(target.text, source.text).filter { it.isNotBlank() }.joinToString("\n"),
    width = maxOf(target.width, source.width),
)

fun hasRemoteDeleteConflict(local: SharedTextBlock?, deletedSnapshot: SharedTextBlock?): Boolean =
    local != null &&
        local.text.isNotBlank() &&
        (deletedSnapshot == null || local.text != deletedSnapshot.text)

data class CanvasBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

fun estimatedBlockHeight(block: SharedTextBlock, textScale: Float = 1f): Float {
    val charactersPerLine = (block.width / 9.2f).coerceAtLeast(12f)
    val explicitLines = block.text.lines().sumOf { line ->
        ceil(line.length.coerceAtLeast(1) / charactersPerLine).toInt()
    }
    val contentHeight = (62f + explicitLines * 24f * textScale.coerceIn(.75f, 1.6f)).coerceAtLeast(96f)
    return maxOf(contentHeight, block.height ?: 0f)
}

fun findAvailableBlockPosition(
    preferredX: Float,
    preferredY: Float,
    width: Float,
    blocks: List<SharedTextBlock>,
    visibleBounds: CanvasBounds? = null,
): Offset {
    val prototype = SharedTextBlock("candidate", "", preferredX, preferredY, width)
    val candidateHeight = estimatedBlockHeight(prototype)
    val startX = visibleBounds?.let {
        preferredX.coerceIn(it.left + 12f, (it.right - width - 12f).coerceAtLeast(it.left + 12f))
    } ?: preferredX
    val startY = visibleBounds?.let {
        preferredY.coerceIn(it.top + 112f, (it.bottom - candidateHeight - 112f).coerceAtLeast(it.top + 112f))
    } ?: preferredY

    fun isFree(x: Float, y: Float): Boolean {
        val insideVisibleArea = visibleBounds?.let {
            x >= it.left + 12f && x + width <= it.right - 12f &&
                y >= it.top + 96f && y + candidateHeight <= it.bottom - 104f
        } ?: true
        return insideVisibleArea && blocks.none { other ->
            rectanglesOverlap(
            x,
            y,
            width,
            candidateHeight,
            other.x,
            other.y,
            other.width,
            estimatedBlockHeight(other),
            padding = 12f,
        )
        }
    }

    if (isFree(startX, startY)) return Offset(startX, startY)
    // Golden-angle spiral: collision avoidance without invisible rows, columns,
    // or fixed slots. Every candidate is a continuous canvas coordinate.
    val goldenAngle = Math.toRadians(137.507764).toFloat()
    for (index in 1..2400) {
        val radius = 18f + sqrt(index.toFloat()) * 18f
        val angle = index * goldenAngle
        val candidateX = startX + cos(angle) * radius
        val candidateY = startY + sin(angle) * radius
        if (isFree(candidateX, candidateY)) return Offset(candidateX, candidateY)
    }
    return Offset(startX, startY)
}

private fun rectanglesOverlap(
    ax: Float,
    ay: Float,
    aw: Float,
    ah: Float,
    bx: Float,
    by: Float,
    bw: Float,
    bh: Float,
    padding: Float,
): Boolean = ax < bx + bw + padding &&
    ax + aw + padding > bx &&
    ay < by + bh + padding &&
    ay + ah + padding > by
