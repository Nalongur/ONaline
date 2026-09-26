package space.privatecanvas.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

private sealed interface PendingExportRequest {
    data class Archive(val password: String) : PendingExportRequest
    data class Pdf(val includeTimestamps: Boolean) : PendingExportRequest
    data object Image : PendingExportRequest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(
    state: PrivateCanvasState,
    contentPadding: PaddingValues,
    viewModel: PrivateCanvasViewModel,
) {
    val contact = state.activeContact ?: return
    val readOnly = contact.readOnly
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val topInset = 0.dp
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showSession by rememberSaveable { mutableStateOf(false) }
    var showEmoji by rememberSaveable { mutableStateOf(false) }
    var showCopyTargets by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<SharedTextBlock?>(null) }
    var mergeSource by remember { mutableStateOf<SharedTextBlock?>(null) }
    val selected = state.activeBlocks.firstOrNull { it.id == state.selectedBlockId }
    var viewportX by remember(contact.id) { mutableFloatStateOf(state.viewportOffset.x) }
    var viewportY by remember(contact.id) { mutableFloatStateOf(state.viewportOffset.y) }
    var viewportScale by remember(contact.id) { mutableFloatStateOf(state.viewportScale) }
    var activeDragBlockId by remember { mutableStateOf<String?>(null) }
    var activeDragX by remember { mutableFloatStateOf(0f) }
    var activeDragY by remember { mutableFloatStateOf(0f) }
    var frameDrawingMode by rememberSaveable(contact.id) { mutableStateOf(false) }
    var frameEraseMode by rememberSaveable(contact.id) { mutableStateOf(false) }
    var draftFramePoints by remember(contact.id) { mutableStateOf<List<CanvasPoint>>(emptyList()) }
    var deleteFrameCandidate by remember { mutableStateOf<HandDrawnFrame?>(null) }
    var pendingArchive by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExportAuthorization by remember { mutableStateOf<PendingExportRequest?>(null) }
    var clearAuthorizationPending by remember { mutableStateOf(false) }
    val archiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingArchive
        pendingArchive = null
        if (uri != null && bytes != null && writeExportFile(context, uri, bytes)) {
            viewModel.recordSuccessfulExport("pcanvas")
            viewModel.showNotice("ENCRYPTED ARCHIVE SAVED")
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = pendingPdf
        pendingPdf = null
        if (uri != null && bytes != null && writeExportFile(context, uri, bytes)) {
            viewModel.recordSuccessfulExport("pdf")
            viewModel.showNotice("PDF SAVED")
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingImage
        pendingImage = null
        if (uri != null && bytes != null && writeExportFile(context, uri, bytes)) {
            viewModel.recordSuccessfulExport("png")
            viewModel.showNotice("IMAGE SAVED")
        }
    }
    val exportAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pendingExportAuthorization
        pendingExportAuthorization = null
        if (result.resultCode != Activity.RESULT_OK || request == null) {
            if (request != null) viewModel.showNotice("EXPORT NOT CREATED")
            return@rememberLauncherForActivityResult
        }
        when (request) {
            is PendingExportRequest.Archive -> viewModel.createEncryptedArchive(request.password)
                .onSuccess { bytes ->
                    pendingArchive = bytes
                    archiveLauncher.launch("${safeFileName(contact.displayName)}.pcanvas")
                }
                .onFailure { viewModel.showNotice("PASSWORD MUST BE AT LEAST 8 CHARACTERS") }
            is PendingExportRequest.Pdf -> viewModel.createPdfExport(request.includeTimestamps)
                .onSuccess { bytes ->
                    pendingPdf = bytes
                    pdfLauncher.launch("${safeFileName(contact.displayName)}.pdf")
                }
                .onFailure { viewModel.showNotice("EXPORT NOT CREATED") }
            PendingExportRequest.Image -> viewModel.createImageExport()
                .onSuccess { bytes ->
                    pendingImage = bytes
                    imageLauncher.launch("${safeFileName(contact.displayName)}.png")
                }
                .onFailure { viewModel.showNotice("EXPORT NOT CREATED") }
        }
    }
    val requestAuthorizedExport: (PendingExportRequest) -> Unit = { request ->
        if (!keyguardManager.isDeviceSecure) {
            viewModel.showNotice("SET A DEVICE SCREEN LOCK FIRST")
        } else {
            pendingExportAuthorization = request
            showExport = false
            exportAuthLauncher.launch(
                keyguardManager.createConfirmDeviceCredentialIntent(
                    state.language.text("Export private space", "导出私人空间"),
                    state.language.text("Confirm your device credential", "请验证设备凭据"),
                ),
            )
        }
    }
    val clearAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val shouldClear = clearAuthorizationPending
        clearAuthorizationPending = false
        if (result.resultCode == Activity.RESULT_OK && shouldClear) viewModel.requestClearForBoth()
        else if (shouldClear) viewModel.showNotice("CLEAR REQUEST NOT SENT")
    }

    LaunchedEffect(state.requestedExportContactId) {
        if (state.requestedExportContactId == contact.id) {
            showExport = true
            viewModel.consumeExportRequest()
        }
    }

    LaunchedEffect(contact.id) {
        snapshotFlow { Triple(viewportX, viewportY, viewportScale) }
            .drop(1)
            .collectLatest { (x, y, scale) ->
                delay(180)
                viewModel.setViewport(x, y, scale)
            }
    }

    OrganicBackground(state.theme, Modifier.fillMaxSize().padding(contentPadding)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
        ) {
            val canvasWidthPx = constraints.maxWidth
            val canvasHeightPx = constraints.maxHeight
            val backgroundGestures = if (frameDrawingMode && !readOnly) {
                Modifier.pointerInput(state.activeContactId, viewportX, viewportY, viewportScale) {
                    fun toCanvasPoint(position: Offset) = CanvasPoint(
                        (position.x - viewportX) / viewportScale / density.density,
                        (position.y - viewportY) / viewportScale / density.density,
                    )
                    detectDragGestures(
                        onDragStart = { position ->
                            focusManager.clearFocus(force = true)
                            viewModel.selectBlock(null)
                            draftFramePoints = listOf(toCanvasPoint(position))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            draftFramePoints = draftFramePoints + toCanvasPoint(change.position)
                        },
                        onDragEnd = {
                            viewModel.addHandDrawnFrame(draftFramePoints)
                            draftFramePoints = emptyList()
                            frameDrawingMode = false
                        },
                        onDragCancel = {
                            draftFramePoints = emptyList()
                            frameDrawingMode = false
                        },
                    )
                }
            } else {
                Modifier
                    .pointerInput(state.activeContactId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            viewportX += pan.x
                            viewportY += pan.y
                            viewportScale = (viewportScale * zoom).coerceIn(.25f, 3f)
                        }
                    }
                    .pointerInput(state.activeContactId) {
                        detectTapGestures(
                            onTap = {
                                focusManager.clearFocus(force = true)
                                viewModel.selectBlock(null)
                            },
                            onDoubleTap = { position ->
                                if (readOnly) return@detectTapGestures
                                val x = ((position.x - viewportX) / viewportScale / density.density)
                                val y = ((position.y - viewportY) / viewportScale / density.density)
                                viewModel.createBlock(
                                    x,
                                    y,
                                    visibleBounds = visibleCanvasBounds(
                                        canvasWidthPx,
                                        canvasHeightPx,
                                        viewportX,
                                        viewportY,
                                        viewportScale,
                                        density.density,
                                    ),
                                )
                            },
                        )
                    }
            }
            Box(Modifier.matchParentSize().then(backgroundGestures))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = viewportX
                        translationY = viewportY
                        scaleX = viewportScale
                        scaleY = viewportScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                state.activeFrames.forEach { frame -> DashedFreehandFrame(frame.points, state.theme) }
                if (draftFramePoints.size >= 2) {
                    DashedFreehandFrame(draftFramePoints, state.theme, preview = true)
                }
                state.activeBlocks.forEach { block ->
                    val movesWithActiveDrag = activeDragBlockId == block.id
                    SharedBlockCard(
                        block = block,
                        readOnly = readOnly,
                        selected = block.id == state.selectedBlockId,
                        theme = state.theme,
                        textScale = density.fontScale,
                        viewportScale = viewportScale,
                        dragX = if (movesWithActiveDrag) activeDragX else 0f,
                        dragY = if (movesWithActiveDrag) activeDragY else 0f,
                        onSelect = { viewModel.selectBlock(block.id) },
                        onTextChange = { viewModel.updateBlockText(block.id, it) },
                        onMoveStart = {
                            focusManager.clearFocus(force = true)
                            activeDragBlockId = block.id
                            activeDragX = 0f
                            activeDragY = 0f
                        },
                        onMove = { drag ->
                            activeDragX += drag.x
                            activeDragY += drag.y
                        },
                        onMoveCancel = {
                            activeDragBlockId = null
                            activeDragX = 0f
                            activeDragY = 0f
                        },
                        onMoveCommit = {
                            viewModel.commitBlockMove(
                                block.id,
                                activeDragX / density.density / viewportScale,
                                activeDragY / density.density / viewportScale,
                            )
                            activeDragBlockId = null
                            activeDragX = 0f
                            activeDragY = 0f
                        },
                        onResizeCommit = { corner, pixelDelta ->
                            viewModel.resizeBlockFromCorner(
                                block.id,
                                corner,
                                Offset(
                                    pixelDelta.x / density.density / viewportScale,
                                    pixelDelta.y / density.density / viewportScale,
                                ),
                            )
                        },
                    )
                }
            }

            if (frameEraseMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state.activeFrames, viewportX, viewportY, viewportScale) {
                            detectTapGestures { position ->
                                val canvasPoint = CanvasPoint(
                                    (position.x - viewportX) / viewportScale / density.density,
                                    (position.y - viewportY) / viewportScale / density.density,
                                )
                                state.activeFrames.asReversed()
                                    .firstOrNull { it.containsOrTouches(canvasPoint) }
                                    ?.let { frame ->
                                        deleteFrameCandidate = frame
                                        frameEraseMode = false
                                    }
                            }
                        },
                )
            }

            CanvasTopBar(
                contact = contact,
                mode = state.canvasMode,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
                onBack = viewModel::endSession,
                onSession = { showSession = true },
            )

            StatusPill(
                label = if (readOnly) ui("RECOVERED · READ ONLY", "已恢复 · 只读") else
                    ui(if (state.canvasMode == CanvasMode.Live) "2 LIVE" else "OFFLINE EDITING"),
                color = if (state.canvasMode == CanvasMode.Live) Sage else MutedInk,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 72.dp),
            )

            if (frameEraseMode) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 118.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = .9f),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        ui("Tap the dashed frame to delete", "点击要删除的虚线框"),
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 12.dp)
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    visible = selected != null && !readOnly,
                    enter = if (state.reduceMotion) EnterTransition.None else fadeIn(),
                    exit = if (state.reduceMotion) ExitTransition.None else fadeOut(),
                ) {
                    SelectedBlockStrip(
                        block = selected,
                        isOffline = state.canvasMode == CanvasMode.Offline,
                        onPin = { selected?.let { viewModel.togglePin(it.id) } },
                        onMerge = { selected?.let { mergeSource = it } },
                        onDelete = { deleteCandidate = selected },
                    )
                }
                Spacer(Modifier.height(8.dp))
                ToolDock(
                    hasSelection = selected != null,
                    frameDrawingMode = frameDrawingMode,
                    readOnly = readOnly,
                    onEmoji = { showEmoji = true },
                    onCreate = {
                        val centerX = selected?.let { it.x + 24f }
                            ?: (((canvasWidthPx / 2f - viewportX) / viewportScale / density.density) - 115f)
                        val centerY = selected?.let { it.y + estimatedBlockHeight(it, density.fontScale) + 24f }
                            ?: (((canvasHeightPx / 2f - viewportY) / viewportScale / density.density) - 52f)
                        val created = viewModel.createBlock(
                            centerX,
                            centerY,
                            visibleBounds = visibleCanvasBounds(
                                canvasWidthPx,
                                canvasHeightPx,
                                viewportX,
                                viewportY,
                                viewportScale,
                                density.density,
                            ),
                        )
                        if (created != null && selected == null) {
                            viewportX = canvasWidthPx / 2f -
                                (created.x + created.width / 2f) * density.density * viewportScale
                            viewportY = canvasHeightPx / 2f -
                                (created.y + estimatedBlockHeight(created, density.fontScale) / 2f) * density.density * viewportScale
                        }
                    },
                    onHidden = { selected?.let { viewModel.beginHiddenInput(it.id) } },
                    onFrame = {
                        focusManager.clearFocus(force = true)
                        viewModel.selectBlock(null)
                        draftFramePoints = emptyList()
                        frameEraseMode = false
                        frameDrawingMode = !frameDrawingMode
                    },
                    onOverview = {
                        viewportX = 0f
                        viewportY = 0f
                        viewportScale = 1f
                    },
                    onMore = { showMore = true },
                )
            }
        }
    }

    if (state.hiddenInputBlockId != null) {
        HiddenInputSheet(
            value = state.hiddenDrafts[state.hiddenInputBlockId].orEmpty(),
            onValueChange = viewModel::updateHiddenDraft,
            onReveal = viewModel::revealHiddenDraft,
            onKeep = viewModel::keepHiddenDraft,
            onDiscard = viewModel::discardHiddenDraft,
        )
    }

    if (showEmoji) {
        EmojiPickerSheet(
            enabled = selected != null,
            onDismiss = { showEmoji = false },
            onEmoji = { emoji ->
                selected?.let { viewModel.appendText(it.id, emoji) }
                showEmoji = false
            },
        )
    }

    if (showMore) {
        CanvasMoreSheet(
            selected = selected,
            mode = state.canvasMode,
            hasFrames = state.activeFrames.isNotEmpty(),
            readOnly = readOnly,
            onDismiss = { showMore = false },
            onMerge = {
                mergeSource = selected
                showMore = false
            },
            onHidden = {
                selected?.let { viewModel.beginHiddenInput(it.id) }
                showMore = false
            },
            onDelete = {
                deleteCandidate = selected
                showMore = false
            },
            onDeleteFrame = {
                showMore = false
                frameDrawingMode = false
                frameEraseMode = true
                viewModel.selectBlock(null)
            },
            onCopy = {
                showMore = false
                showCopyTargets = true
            },
            onExport = {
                showMore = false
                showExport = true
            },
            onRequestClear = {
                showMore = false
                if (!keyguardManager.isDeviceSecure) {
                    viewModel.showNotice("SET A DEVICE SCREEN LOCK FIRST")
                } else {
                    clearAuthorizationPending = true
                    clearAuthLauncher.launch(
                        keyguardManager.createConfirmDeviceCredentialIntent(
                            state.language.text("Request shared clear", "请求双方清空"),
                            state.language.text("Confirm your device credential", "请验证设备凭据"),
                        ),
                    )
                }
            },
            onAppearance = {
                showMore = false
                showAppearance = true
            },
        )
    }

    if (showCopyTargets) {
        val destinations = state.contacts.filter { !it.readOnly && !it.blocked }
        AlertDialog(
            onDismissRequest = { showCopyTargets = false },
            icon = { Icon(Icons.Outlined.ContentCopy, null) },
            title = { Text(ui("COPY INTO PRIVATE SPACE", "复制到私人空间")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (selected == null) {
                        Text(ui("Select a block first", "请先选择一个气泡"), color = MutedInk)
                    } else if (destinations.isEmpty()) {
                        Text(ui("No writable private space is available.", "当前没有可写入的私人空间。"), color = MutedInk)
                    } else {
                        destinations.forEach { destination ->
                            SheetAction(
                                Icons.Outlined.ContentCopy,
                                destination.displayName,
                                ui("Create a new independent block", "创建一个新的独立气泡"),
                                {
                                    showCopyTargets = false
                                    viewModel.copyRecoveredBlockToContact(selected.id, destination.id)
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCopyTargets = false }) { Text(ui("CANCEL", "取消")) }
            },
        )
    }

    if (showExport) {
        ExportSheet(
            onDismiss = { showExport = false },
            onArchive = { password ->
                requestAuthorizedExport(PendingExportRequest.Archive(password))
            },
            onPdf = { includeTimestamps ->
                requestAuthorizedExport(PendingExportRequest.Pdf(includeTimestamps))
            },
            onImage = {
                requestAuthorizedExport(PendingExportRequest.Image)
            },
        )
    }

    if (showAppearance) {
        AppearanceSheet(
            theme = state.theme,
            language = state.language,
            textSize = state.textSize,
            highContrast = state.highContrast,
            reduceMotion = state.reduceMotion,
            connectionRequestTimeoutSeconds = state.connectionRequestTimeoutSeconds,
            contactSortMode = state.contactSortMode,
            localDisplayName = state.localDisplayName,
            appLockEnabled = state.appLockEnabled,
            blockScreenshots = state.blockScreenshots,
            genericNotificationsEnabled = state.genericNotificationsEnabled,
            diagnostics = state.diagnostics(),
            serverUrl = state.serverUrl,
            previousServerUrl = state.previousServerUrl,
            onThemeChange = viewModel::setTheme,
            onLanguageChange = viewModel::setLanguage,
            onTextSizeChange = viewModel::setTextSize,
            onHighContrastChange = viewModel::setHighContrast,
            onReduceMotionChange = viewModel::setReduceMotion,
            onConnectionRequestTimeoutChange = viewModel::setConnectionRequestTimeout,
            onContactSortModeChange = viewModel::setContactSortMode,
            onLocalDisplayNameChange = viewModel::updateLocalDisplayName,
            onRemoveAllLocalContent = viewModel::removeAllLocalContent,
            onAppLockChange = viewModel::setAppLockEnabled,
            onBlockScreenshotsChange = viewModel::setBlockScreenshots,
            onGenericNotificationsChange = viewModel::setGenericNotificationsEnabled,
            onRestoreArchiveClick = { viewModel.showNotice("RESTORE FROM CONTACTS SETTINGS") },
            onServerUrlChange = viewModel::migrateServer,
            onRollbackServer = viewModel::rollbackServerMigration,
            onDismiss = { showAppearance = false },
        )
    }

    if (showSession) {
        SessionSheet(
            contact = contact,
            mode = state.canvasMode,
            onDismiss = { showSession = false },
            onEnd = {
                showSession = false
                viewModel.endSession()
            },
        )
    }

    deleteCandidate?.let { block ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text(ui("DELETE THIS BLOCK?")) },
            text = { Text(ui("The block can be restored from the short undo action. Concurrent offline text is recovered into a separate block.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    viewModel.deleteBlock(block.id)
                }) { Text(ui("DELETE")) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(ui("CANCEL")) } },
        )
    }

    deleteFrameCandidate?.let { frame ->
        AlertDialog(
            onDismissRequest = { deleteFrameCandidate = null },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text(ui("DELETE DASHED FRAME?", "删除这个虚线框？")) },
            text = { Text(ui("Only this hand-drawn frame will be removed.", "只会删除当前选中的手绘虚线框。")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteFrameCandidate = null
                    viewModel.deleteHandDrawnFrame(frame.id)
                }) { Text(ui("DELETE")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFrameCandidate = null }) { Text(ui("CANCEL")) }
            },
        )
    }

    mergeSource?.let { source ->
        MergeTargetDialog(
            source = source,
            targets = state.activeBlocks.filterNot { it.id == source.id },
            offline = state.canvasMode == CanvasMode.Offline,
            onDismiss = { mergeSource = null },
            onMerge = { targetId ->
                mergeSource = null
                viewModel.mergeBlocks(source.id, targetId)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerSheet(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onEmoji: (String) -> Unit,
) {
    val emojis = listOf(
        "😀", "😂", "🥹", "😍", "🥰", "😎", "🤔", "😭",
        "👍", "👏", "🙏", "💪", "🤝", "❤️", "💛", "💙",
        "✨", "🎉", "🌿", "🌙", "☀️", "🍲", "☕", "📌",
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF4F1EB)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(ui("CHOOSE EMOJI", "选择表情"), style = MaterialTheme.typography.titleLarge)
            Text(
                if (enabled) ui("Insert into the selected block", "插入到当前气泡") else ui("Select a block first", "请先选择一个气泡"),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
            Spacer(Modifier.height(16.dp))
            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(145.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(emojis) { emoji ->
                    Surface(
                        onClick = { if (enabled) onEmoji(" $emoji") },
                        modifier = Modifier.size(43.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = if (enabled) .72f else .35f),
                    ) { Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 23.sp) } }
                }
            }
        }
    }
}

@Composable
private fun CanvasTopBar(
    contact: TrustedContact,
    mode: CanvasMode,
    modifier: Modifier,
    onBack: () -> Unit,
    onSession: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = .66f),
        border = BorderStroke(0.dp, Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, ui("Back to contacts")) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (mode == CanvasMode.Live) Sage else MutedInk)
                Spacer(Modifier.width(8.dp))
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = onSession) { Icon(Icons.Outlined.PeopleOutline, ui("Session details")) }
        }
    }
}

@Composable
private fun DashedFreehandFrame(
    points: List<CanvasPoint>,
    theme: CanvasTheme,
    preview: Boolean = false,
) {
    if (points.size < 2) return
    val highContrast = LocalHighContrast.current
    val padding = 8f
    val left = points.minOf { it.x } - padding
    val top = points.minOf { it.y } - padding
    val right = points.maxOf { it.x } + padding
    val bottom = points.maxOf { it.y } + padding
    Canvas(
        Modifier
            .offset(left.dp, top.dp)
            .requiredSize((right - left).coerceAtLeast(1f).dp, (bottom - top).coerceAtLeast(1f).dp),
    ) {
        val path = Path().apply {
            moveTo((points.first().x - left).dp.toPx(), (points.first().y - top).dp.toPx())
            points.drop(1).forEach { point ->
                lineTo((point.x - left).dp.toPx(), (point.y - top).dp.toPx())
            }
            if (!preview) close()
        }
        drawPath(
            path = path,
            color = if (highContrast) Ink.copy(alpha = .78f) else theme.accentColor().copy(alpha = if (preview) .8f else .56f),
            style = Stroke(
                width = if (highContrast) 2.5.dp.toPx() else if (preview) 2.dp.toPx() else 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())),
            ),
        )
    }
}

@Composable
private fun SharedBlockCard(
    block: SharedTextBlock,
    readOnly: Boolean,
    selected: Boolean,
    theme: CanvasTheme,
    textScale: Float,
    viewportScale: Float,
    dragX: Float,
    dragY: Float,
    onSelect: () -> Unit,
    onTextChange: (String) -> Unit,
    onMoveStart: () -> Unit,
    onMove: (Offset) -> Unit,
    onMoveCancel: () -> Unit,
    onMoveCommit: () -> Unit,
    onResizeCommit: (ResizeCorner, Offset) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val highContrast = LocalHighContrast.current
    val density = LocalDensity.current
    val focusRequester = remember(block.id) { FocusRequester() }
    var resizeCorner by remember(block.id) { mutableStateOf<ResizeCorner?>(null) }
    var resizePixelsX by remember(block.id) { mutableFloatStateOf(0f) }
    var resizePixelsY by remember(block.id) { mutableFloatStateOf(0f) }
    val resizeCanvasDeltaX = resizePixelsX / density.density / viewportScale
    val resizeCanvasDeltaY = resizePixelsY / density.density / viewportScale
    val resizingFromLeft = resizeCorner == ResizeCorner.TopLeft || resizeCorner == ResizeCorner.BottomLeft
    val resizingFromTop = resizeCorner == ResizeCorner.TopLeft || resizeCorner == ResizeCorner.TopRight
    val previewWidth = (block.width + if (resizingFromLeft) -resizeCanvasDeltaX else resizeCanvasDeltaX).coerceIn(150f, 360f)
    val previewX = block.x + if (resizingFromLeft) block.width - previewWidth else 0f
    val contentHeight = estimatedBlockHeight(block.copy(width = previewWidth, height = null), textScale)
    val currentHeight = estimatedBlockHeight(block, textScale)
    val previewHeight = (currentHeight + if (resizingFromTop) -resizeCanvasDeltaY else resizeCanvasDeltaY)
        .coerceIn(contentHeight, 520f)
    val previewY = block.y + if (resizingFromTop) currentHeight - previewHeight else 0f

    LaunchedEffect(block.id, selected) {
        if (selected && block.text.isEmpty() && !readOnly) focusRequester.requestFocus()
    }

    fun Modifier.moveHandle(): Modifier = pointerInput(block.id, block.pinned) {
        if (block.pinned || readOnly) return@pointerInput
        detectDragGestures(
            onDragStart = { onSelect(); onMoveStart() },
            onDragEnd = onMoveCommit,
            onDragCancel = onMoveCancel,
            onDrag = { change, dragAmount ->
                change.consume()
                onMove(dragAmount)
            },
        )
    }

    fun Modifier.resizeHandle(corner: ResizeCorner): Modifier = pointerInput(block.id, corner, viewportScale) {
        if (readOnly) return@pointerInput
        detectDragGestures(
            onDragStart = {
                onSelect()
                resizeCorner = corner
                resizePixelsX = 0f
                resizePixelsY = 0f
            },
            onDragEnd = {
                onResizeCommit(corner, Offset(resizePixelsX, resizePixelsY))
                resizeCorner = null
                resizePixelsX = 0f
                resizePixelsY = 0f
            },
            onDragCancel = {
                resizeCorner = null
                resizePixelsX = 0f
                resizePixelsY = 0f
            },
        ) { change, dragAmount ->
            change.consume()
            resizePixelsX += dragAmount.x
            resizePixelsY += dragAmount.y
        }
    }

    Box(
        modifier = Modifier
            .offset(x = previewX.dp, y = previewY.dp)
            .graphicsLayer {
                translationX = dragX
                translationY = dragY
            }
            .width(previewWidth.dp)
            .height(previewHeight.dp)
            .shadow(if (selected) 10.dp else 4.dp, shape, ambientColor = Color.Black.copy(alpha = .08f))
            .background(Color.White, shape)
            .border(
                width = if (highContrast) 2.dp else if (selected) 1.5.dp else 1.dp,
                color = when {
                    highContrast && selected -> Ink
                    highContrast -> MutedInk
                    selected -> theme.accentColor().copy(alpha = .72f)
                    else -> Color.White.copy(alpha = .9f)
                },
                shape = shape,
            )
            .clip(shape)
            .pointerInput(block.id) {
                detectTapGestures(onTap = { onSelect() })
            },
    ) {
        val selectionColors = remember {
            TextSelectionColors(
                handleColor = MutedBlue,
                backgroundColor = MutedBlue.copy(alpha = .10f),
            )
        }
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = block.text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 42.dp, top = 28.dp, bottom = 22.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onSelect() },
                readOnly = readOnly,
                textStyle = TextStyle(color = Ink, fontSize = 17.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(MutedBlue),
                decorationBox = { inner ->
                    Box {
                        if (block.text.isEmpty()) {
                            Text(ui("Start writing…"), color = MutedInk.copy(alpha = .66f), style = MaterialTheme.typography.bodyLarge)
                        }
                        inner()
                    }
                },
            )
        }
        if (block.isRecovered) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color = theme.accentColor().copy(alpha = .13f),
            ) {
                Text(
                    ui("RECOVERED", "已恢复"),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(24.dp)
                .moveHandle(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .width(28.dp)
                    .height(3.dp)
                    .background(MutedInk.copy(alpha = if (selected) .34f else .15f), CircleShape),
            )
        }
        if (selected && !readOnly) {
            listOf(
                Alignment.TopStart to ResizeCorner.TopLeft,
                Alignment.TopEnd to ResizeCorner.TopRight,
                Alignment.BottomStart to ResizeCorner.BottomLeft,
                Alignment.BottomEnd to ResizeCorner.BottomRight,
            ).forEach { (alignment, corner) ->
                Box(
                    Modifier
                        .align(alignment)
                        // Keep the visual dot quiet while giving fingers a reliable
                        // corner target for simultaneous horizontal/vertical resize.
                        .size(44.dp)
                        .resizeHandle(corner),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(Color.White, CircleShape)
                            .border(1.5.dp, theme.accentColor().copy(alpha = .72f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedBlockStrip(
    block: SharedTextBlock?,
    isOffline: Boolean,
    onPin: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    if (block == null) return
    AcrylicSurface(Modifier.widthIn(max = 350.dp)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            DockAction(Icons.Outlined.PushPin, ui(if (block.pinned) "Unpin block" else "Pin block"), onPin, selected = block.pinned)
            DockAction(Icons.AutoMirrored.Outlined.MergeType, ui(if (isOffline) "Merge unavailable offline" else "Merge block"), onMerge)
            DockAction(Icons.Outlined.DeleteOutline, ui("Delete block"), onDelete)
        }
    }
}

@Composable
private fun ToolDock(
    hasSelection: Boolean,
    frameDrawingMode: Boolean,
    readOnly: Boolean,
    onEmoji: () -> Unit,
    onCreate: () -> Unit,
    onHidden: () -> Unit,
    onFrame: () -> Unit,
    onOverview: () -> Unit,
    onMore: () -> Unit,
) {
    AcrylicSurface(Modifier.widthIn(max = 350.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DockAction(Icons.Outlined.EmojiEmotions, ui("Insert emoji"), onEmoji, enabled = !readOnly)
            DockAction(Icons.Outlined.Add, ui("Create block"), onCreate, enabled = !readOnly)
            DockAction(Icons.Outlined.VisibilityOff, ui("Hidden input"), onHidden, selected = hasSelection, enabled = !readOnly)
            DockAction(Icons.Outlined.Gesture, ui("Draw dashed frame", "绘制虚线框"), onFrame, selected = frameDrawingMode, enabled = !readOnly)
            DockAction(Icons.Outlined.CenterFocusStrong, ui("Canvas overview"), onOverview)
            DockAction(Icons.Outlined.MoreHoriz, ui("More actions"), onMore)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenInputSheet(
    value: String,
    onValueChange: (String) -> Unit,
    onReveal: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onKeep, containerColor = Color(0xFFF1EFE9)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VisibilityOff, null, tint = Sage)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(ui("HIDDEN INPUT"), style = MaterialTheme.typography.titleLarge)
                    Text(ui("ONLY VISIBLE ON THIS DEVICE"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = { Text(ui("Write privately, then reveal it as one change…")) },
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(14.dp))
            PrimaryPillButton(ui("REVEAL"), onReveal, Modifier.fillMaxWidth(), enabled = value.isNotBlank())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDiscard) { Text(ui("DISCARD")) }
                TextButton(onClick = onKeep) { Text(ui("KEEP DRAFT")) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasMoreSheet(
    selected: SharedTextBlock?,
    mode: CanvasMode,
    hasFrames: Boolean,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onMerge: () -> Unit,
    onHidden: () -> Unit,
    onDelete: () -> Unit,
    onDeleteFrame: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onRequestClear: () -> Unit,
    onAppearance: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF1EFE9)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ui("CANVAS ACTIONS"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(5.dp))
            SheetAction(
                Icons.Outlined.DeleteSweep,
                ui("DELETE DASHED FRAME", "删除虚线框"),
                if (hasFrames) ui("Then tap the target frame", "随后在画布上点击目标虚线框") else ui("No dashed frames", "当前没有虚线框"),
                onDeleteFrame,
                enabled = hasFrames && !readOnly,
            )
            SheetAction(
                Icons.AutoMirrored.Outlined.MergeType,
                ui("MERGE"),
                when {
                    selected == null -> ui("Select a block first")
                    mode == CanvasMode.Offline -> ui("Unavailable during offline editing")
                    else -> null
                },
                onMerge,
                enabled = selected != null && mode == CanvasMode.Live && !readOnly,
            )
            SheetAction(Icons.Outlined.VisibilityOff, ui("HIDE INPUT"), if (selected == null) ui("Select a block first") else null, onHidden, enabled = selected != null && !readOnly)
            SheetAction(Icons.Outlined.DeleteOutline, ui("DELETE"), if (selected == null) ui("Select a block first") else ui("Confirmation required"), onDelete, enabled = selected != null && !readOnly)
            if (readOnly) {
                SheetAction(
                    Icons.Outlined.ContentCopy,
                    ui("COPY INTO PRIVATE SPACE", "复制到私人空间"),
                    if (selected == null) ui("Select a block first") else ui("Create a new independent block", "创建一个新的独立气泡"),
                    onCopy,
                    enabled = selected != null,
                )
            }
            Spacer(Modifier.height(5.dp))
            SheetAction(Icons.Outlined.Share, ui("EXPORT SPACE"), ui("Encrypted archive, PDF or image"), onExport)
            if (!readOnly) {
                SheetAction(
                    Icons.Outlined.DeleteSweep,
                    ui("REQUEST CLEAR FOR BOTH", "请求双方清空"),
                    if (mode == CanvasMode.Live) ui("Partner approval and device credential required", "需要对方批准和设备凭据")
                    else ui("Both people must be live", "需要双方在线"),
                    onRequestClear,
                    enabled = mode == CanvasMode.Live,
                )
            }
            SheetAction(Icons.Outlined.Palette, ui("APPEARANCE"), ui("Local theme and privacy"), onAppearance)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    onDismiss: () -> Unit,
    onArchive: (String) -> Unit,
    onPdf: (Boolean) -> Unit,
    onImage: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var plaintextConfirmed by rememberSaveable { mutableStateOf(false) }
    var includeTimestamps by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF1EFE9)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui("EXPORT SPACE"), style = MaterialTheme.typography.titleLarge)
            Text(ui("CHOOSE A PRIVATE OUTPUT", "选择私密导出方式"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(ui("ARCHIVE PASSWORD", "归档密码")) },
                supportingText = { Text(ui("At least 8 characters; it cannot be recovered", "至少 8 个字符；密码无法找回")) },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
            )
            SheetAction(
                Icons.Outlined.Archive,
                ui("SAVE ENCRYPTED ARCHIVE", "保存加密归档"),
                ui("AES-256-GCM protected .pcanvas", "AES-256-GCM 保护的 .pcanvas"),
                { onArchive(password) },
                enabled = password.length >= 8,
            )
            Surface(
                onClick = { plaintextConfirmed = !plaintextConfirmed },
                color = if (plaintextConfirmed) Sage.copy(alpha = .2f) else Color.White.copy(alpha = .46f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    ui(
                        if (plaintextConfirmed) "✓ I UNDERSTAND PDF / IMAGE ARE NOT ENCRYPTED" else "PDF / IMAGE CONTAIN READABLE TEXT — TAP TO CONFIRM",
                        if (plaintextConfirmed) "✓ 我了解 PDF / 图片未加密" else "PDF / 图片包含可读文字 — 点击确认",
                    ),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (plaintextConfirmed) Ink else MutedInk,
                )
            }
            Surface(
                onClick = { includeTimestamps = !includeTimestamps },
                color = if (includeTimestamps) Sage.copy(alpha = .18f) else Color.White.copy(alpha = .46f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    ui(
                        if (includeTimestamps) "✓ INCLUDE TIMESTAMPS" else "INCLUDE TIMESTAMPS",
                        if (includeTimestamps) "✓ 包含时间" else "包含时间",
                    ),
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (includeTimestamps) Ink else MutedInk,
                )
            }
            SheetAction(
                Icons.Outlined.PictureAsPdf,
                "PDF",
                ui("Readable region-based pages", "按画布区域清晰分页"),
                { onPdf(includeTimestamps) },
                enabled = plaintextConfirmed,
            )
            SheetAction(Icons.Outlined.Image, ui("IMAGE", "图片"), "PNG", onImage, enabled = plaintextConfirmed)
            Spacer(Modifier.height(7.dp))
            Text(
                ui("Encrypted archives include this contact's blocks, frames and local event history only.", "加密归档仅包含当前联系人的气泡、虚线框和本地事件历史。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
        }
    }
}

private fun writeExportFile(context: android.content.Context, uri: android.net.Uri, bytes: ByteArray): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri, "w")?.use { output -> output.write(bytes) }
        ?: error("Unable to open export destination")
}.isSuccess

private fun safeFileName(value: String): String = normalizeDisplayName(value)
    .replace(Regex("[^A-Za-z0-9._\\-\u4e00-\u9fff]+"), "-")
    .trim('-')
    .ifBlank { "private-canvas" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    contact: TrustedContact,
    mode: CanvasMode,
    onDismiss: () -> Unit,
    onEnd: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF1EFE9)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text(contact.displayName, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(5.dp))
            Text(ui(if (mode == CanvasMode.Live) "CONNECTED / 2 LIVE" else "OFFLINE EDITING", if (mode == CanvasMode.Live) "已连接 / 2 人在线" else "离线编辑"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
            Spacer(Modifier.height(20.dp))
            SheetAction(Icons.Outlined.Lock, ui("PRIVATE SPACE"), ui("Contact identity and canvas remain isolated", "联系人身份和画布相互隔离"), {})
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.AutoMirrored.Outlined.ArrowBack, ui(if (mode == CanvasMode.Live) "END LIVE SESSION" else "BACK TO CONTACTS"), ui("Canvas and local keys will be kept", "画布和本地密钥会被保留"), onEnd)
        }
    }
}

@Composable
private fun MergeTargetDialog(
    source: SharedTextBlock,
    targets: List<SharedTextBlock>,
    offline: Boolean,
    onDismiss: () -> Unit,
    onMerge: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.MergeType, null) },
        title = { Text(ui("MERGE BLOCK")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offline) {
                    Text(ui("Merge is unavailable during offline editing to avoid structural conflicts.", "为避免结构冲突，离线编辑时不可合并。"), color = MutedInk)
                } else if (targets.isEmpty()) {
                    Text(ui("Create another block before merging."), color = MutedInk)
                } else {
                    Text(ui("Choose the target block. Content will join at a paragraph boundary."), color = MutedInk)
                    targets.forEach { target ->
                        Surface(
                            onClick = { onMerge(target.id) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White.copy(alpha = .65f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(target.text.ifBlank { ui("EMPTY BLOCK") }.take(70), modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(ui("CANCEL")) } },
    )
}

private fun visibleCanvasBounds(
    widthPx: Int,
    heightPx: Int,
    viewportX: Float,
    viewportY: Float,
    viewportScale: Float,
    density: Float,
): CanvasBounds = CanvasBounds(
    left = -viewportX / viewportScale / density,
    top = -viewportY / viewportScale / density,
    right = (widthPx - viewportX) / viewportScale / density,
    bottom = (heightPx - viewportY) / viewportScale / density,
)
