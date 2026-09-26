package space.privatecanvas.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MotionPhotosOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: PrivateCanvasState,
    contentPadding: PaddingValues,
    onCreateInvite: () -> PairingInviteDisplay,
    onAcceptInvite: (String, String) -> Boolean,
    onConnect: (String) -> Unit,
    onCancelConnection: (String) -> Unit,
    onOpenOffline: (String) -> Unit,
    onExportContact: (String) -> Unit,
    onDeleteContact: (String) -> Unit,
    onRenameContact: (String, String) -> Unit,
    onSetContactPinned: (String, Boolean) -> Unit,
    onSetContactBlocked: (String, Boolean) -> Unit,
    onVerifyContact: (String) -> Unit,
    onThemeChange: (CanvasTheme) -> Unit,
    onLanguageChange: (UiLanguage) -> Unit,
    onTextSizeChange: (UiTextSize) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onConnectionRequestTimeoutChange: (Int) -> Unit,
    onContactSortModeChange: (ContactSortMode) -> Unit,
    onLocalDisplayNameChange: (String) -> Unit,
    onRemoveAllLocalContent: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onBlockScreenshotsChange: (Boolean) -> Unit,
    onGenericNotificationsChange: (Boolean) -> Unit,
    onRestoreArchive: (ByteArray, String) -> Boolean,
    onPreviewArchive: (ByteArray, String) -> ArchivePreview?,
    onServerMigration: (String) -> Unit,
    onRollbackServer: () -> Unit,
    onShowNotice: (String) -> Unit,
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<TrustedContact?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showRestorePassword by rememberSaveable { mutableStateOf(false) }
    var restorePassword by rememberSaveable { mutableStateOf("") }
    var restorePreview by remember { mutableStateOf<ArchivePreview?>(null) }
    var authenticatedDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val deleteAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val contactId = authenticatedDeleteId
        authenticatedDeleteId = null
        if (result.resultCode == Activity.RESULT_OK && contactId != null) {
            onDeleteContact(contactId)
        } else if (contactId != null) {
            onShowNotice("CONTACT NOT DELETED")
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreBytes = uri?.let { selected ->
            runCatching {
                context.contentResolver.openInputStream(selected)?.use { input ->
                    val bytes = input.readBytes()
                    require(bytes.size <= 20 * 1024 * 1024)
                    bytes
                }
            }.getOrNull()
        }
        showRestorePassword = restoreBytes != null
    }
    val topInset = 0.dp
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    OrganicBackground(state.theme, Modifier.fillMaxSize().padding(contentPadding)) {
        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.language.text("CONTACTS", "联系人"), style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text(state.language.text("TRUSTED PRIVATE SPACES", "可信任的私人空间"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Outlined.Settings, ui("Settings", "设置"), tint = Ink)
                }
                RoundIconButton(Icons.Outlined.Add, ui("Add contact", "添加联系人"), onClick = { showAdd = true })
            }

            if (state.contacts.isEmpty()) {
                EmptyContacts(
                    modifier = Modifier.weight(1f).padding(horizontal = 28.dp, vertical = 18.dp),
                    onAdd = { showAdd = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 12.dp,
                        bottom = bottomInset + 26.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            state.language.text("Tap CONNECT to request a live session. Each contact keeps a separate canvas.", "点击连接以发起实时会话。每位联系人拥有独立画布。"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedInk,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    items(orderedContacts(state.contacts, state.contactSortMode), key = { it.id }) { contact ->
                        ContactCard(
                            contact = contact,
                            language = state.language,
                            onOpenDetail = { detailId = contact.id },
                            onConnect = {
                                if (contact.readOnly) onOpenOffline(contact.id) else onConnect(contact.id)
                            },
                            onCancel = { onCancelConnection(contact.id) },
                        )
                    }
                    item {
                        Surface(
                            onClick = { showAdd = true },
                            color = Color.Transparent,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.PersonAdd, null, tint = MutedInk)
                                Spacer(Modifier.width(12.dp))
                                Text(state.language.text("ADD ANOTHER CONTACT", "添加另一位联系人"), style = MaterialTheme.typography.labelLarge, color = MutedInk)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddContactSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismiss = { showAdd = false },
            onCreateInvite = onCreateInvite,
            onAcceptInvite = { token, name ->
                if (onAcceptInvite(token, name)) showAdd = false
            },
        )
    }

    val detailContact = state.contacts.firstOrNull { it.id == detailId }
    if (detailContact != null) {
        ContactDetailSheet(
            contact = detailContact,
            onDismiss = { detailId = null },
            onConnect = {
                detailId = null
                onConnect(detailContact.id)
            },
            onOpenOffline = {
                detailId = null
                onOpenOffline(detailContact.id)
            },
            onExport = {
                detailId = null
                onExportContact(detailContact.id)
            },
            onConnectionSettings = {
                detailId = null
                showSettings = true
            },
            onRename = { value -> onRenameContact(detailContact.id, value) },
            onSetPinned = { pinned -> onSetContactPinned(detailContact.id, pinned) },
            onSetBlocked = { blocked -> onSetContactBlocked(detailContact.id, blocked) },
            onDelete = {
                detailId = null
                deleteCandidate = detailContact
            },
            onVerify = { onVerifyContact(detailContact.id) },
        )
    }

    if (showSettings) {
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
            onThemeChange = onThemeChange,
            onLanguageChange = onLanguageChange,
            onTextSizeChange = onTextSizeChange,
            onHighContrastChange = onHighContrastChange,
            onReduceMotionChange = onReduceMotionChange,
            onConnectionRequestTimeoutChange = onConnectionRequestTimeoutChange,
            onContactSortModeChange = onContactSortModeChange,
            onLocalDisplayNameChange = onLocalDisplayNameChange,
            onRemoveAllLocalContent = onRemoveAllLocalContent,
            onAppLockChange = onAppLockChange,
            onBlockScreenshotsChange = onBlockScreenshotsChange,
            onGenericNotificationsChange = onGenericNotificationsChange,
            onRestoreArchiveClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*")) },
            onServerUrlChange = onServerMigration,
            onRollbackServer = onRollbackServer,
            onDismiss = { showSettings = false },
        )
    }

    deleteCandidate?.let { contact ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text(ui("DELETE CONTACT", "删除联系人")) },
            text = { Text(ui("This also removes the contact's local canvas and drafts. This cannot be undone.", "这也会删除该联系人的本地画布和草稿，且无法撤销。")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    if (!keyguardManager.isDeviceSecure) {
                        onShowNotice("SET A DEVICE SCREEN LOCK FIRST")
                    } else {
                        authenticatedDeleteId = contact.id
                        deleteAuthLauncher.launch(
                            keyguardManager.createConfirmDeviceCredentialIntent(
                                state.language.text("Delete contact", "删除联系人"),
                                state.language.text("Confirm your device credential", "请验证设备凭据"),
                            ),
                        )
                    }
                }) { Text(ui("DELETE")) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(ui("CANCEL")) } },
        )
    }
    if (showRestorePassword) {
        AlertDialog(
            onDismissRequest = {
                showRestorePassword = false
                restoreBytes = null
                restorePassword = ""
            },
            icon = { Icon(Icons.Outlined.Archive, null) },
            title = { Text(state.language.text("RESTORE ENCRYPTED ARCHIVE", "恢复加密归档")) },
            text = {
                OutlinedTextField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it.take(128) },
                    label = { Text(state.language.text("ARCHIVE PASSWORD", "归档密码")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = restorePassword.length >= 8,
                    onClick = {
                        val preview = restoreBytes?.let { onPreviewArchive(it, restorePassword) }
                        if (preview != null) {
                            showRestorePassword = false
                            restorePreview = preview
                        }
                    },
                ) { Text(state.language.text("RESTORE", "恢复")) }
            },
            dismissButton = { TextButton(onClick = { showRestorePassword = false; restoreBytes = null }) { Text(state.language.text("CANCEL", "取消")) } },
        )
    }
    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                restorePreview = null
                restoreBytes = null
                restorePassword = ""
            },
            icon = { Icon(Icons.Outlined.Archive, null) },
            title = { Text(state.language.text("RESTORE PREVIEW", "恢复预览")) },
            text = {
                Column {
                    Text(state.language.text("Recovered from: ${preview.originalName}", "来源：${preview.originalName}"))
                    Spacer(Modifier.height(8.dp))
                    Text(state.language.text("${preview.blockCount} blocks · ${preview.frameCount} dashed frames", "${preview.blockCount} 个气泡 · ${preview.frameCount} 个虚线框"))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.language.text(
                            "This archive opens as a separate read-only canvas and will not overwrite an active private space.",
                            "归档会作为独立只读画布打开，不会覆盖现有私人空间。",
                        ),
                        color = MutedInk,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val restored = restoreBytes?.let { onRestoreArchive(it, restorePassword) } == true
                    if (restored) {
                        restorePreview = null
                        restoreBytes = null
                        restorePassword = ""
                    }
                }) { Text(state.language.text("RESTORE THIS DEVICE", "恢复到本设备")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    restorePreview = null
                    restoreBytes = null
                    restorePassword = ""
                }) { Text(state.language.text("CANCEL", "取消")) }
            },
        )
    }
}

@Composable
private fun EmptyContacts(modifier: Modifier, onAdd: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AcrylicSurface(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(82.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = .62f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .95f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lock, null, tint = Sage, modifier = Modifier.size(31.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    ui("ADD SOMEONE\nYOU TRUST", "添加你信任的人"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    ui("Each contact opens one separate encrypted space. No phone number or public profile required.", "每位联系人拥有独立的加密空间，无需手机号或公开资料。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(26.dp))
                PrimaryPillButton(ui("ADD CONTACT", "添加联系人"), onAdd, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = Sage, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(ui("LOCAL DATA IS KEYSTORE PROTECTED", "本地数据受密钥库保护"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
                }
            }
        }
    }
}

@Composable
private fun ContactCard(
    contact: TrustedContact,
    language: UiLanguage,
    onOpenDetail: () -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    AcrylicSurface(Modifier.fillMaxWidth().clickable(onClick = onOpenDetail)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = Sage.copy(alpha = .18f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(contact.initials, style = MaterialTheme.typography.titleMedium, color = Ink)
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(contact.displayName, style = MaterialTheme.typography.titleMedium, color = Ink)
                        if (contact.pinned) {
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Outlined.PushPin, ui("Pinned contact", "已置顶联系人"), tint = Sage, modifier = Modifier.size(15.dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (contact.verified) Icons.Outlined.VerifiedUser else Icons.Outlined.Key,
                            null,
                            tint = if (contact.verified) Sage else MutedCoral,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            ui(if (contact.verified) "VERIFIED" else "UNVERIFIED", if (contact.verified) "已验证" else "未验证"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedInk,
                        )
                        Spacer(Modifier.width(10.dp))
                        StatusDot(statusColor(contact.status))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when {
                                contact.readOnly -> language.text("RECOVERED READ ONLY", "已恢复，只读")
                                contact.blocked -> language.text("BLOCKED", "已屏蔽")
                                else -> ui(contact.status.label, when (contact.status) {
                                ConnectionStatus.Available -> "可连接"
                                ConnectionStatus.Requesting -> "请求中"
                                ConnectionStatus.Connected -> "已连接"
                                ConnectionStatus.Offline -> "离线"
                                ConnectionStatus.Busy -> "忙碌"
                                })
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedInk,
                        )
                    }
                }
                IconButton(onClick = onOpenDetail) {
                    Icon(Icons.Outlined.MoreVert, ui("Contact details", "联系人详情"), tint = Ink)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (contact.status == ConnectionStatus.Requesting) {
                Surface(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    color = Color.White.copy(alpha = .6f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.Cancel, null, tint = MutedInk, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(language.text("CANCEL REQUEST", "取消请求"), style = MaterialTheme.typography.labelLarge, color = MutedInk)
                    }
                }
            } else {
                PrimaryPillButton(
                    when {
                        contact.readOnly -> language.text("OPEN READ ONLY", "以只读方式打开")
                        contact.blocked -> language.text("BLOCKED", "已屏蔽")
                        else -> language.text("CONNECT", "连接")
                    },
                    onConnect,
                    Modifier.fillMaxWidth(),
                    enabled = !contact.blocked,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCreateInvite: () -> PairingInviteDisplay,
    onAcceptInvite: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("SHOW") }
    var invite by remember { mutableStateOf(onCreateInvite()) }
    var enteredToken by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFFF4F1EB),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ui("ADD CONTACT", "添加联系人"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(ui("PAIR ONCE, THEN CONNECT DIRECTLY", "配对一次，之后直接连接"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SHOW", "SCAN", "CODE").forEach { item ->
                    Surface(
                        onClick = { mode = item },
                        modifier = Modifier.weight(1f),
                        color = if (mode == item) Sage.copy(alpha = .2f) else Color.White.copy(alpha = .48f),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Text(
                            ui(item, when (item) { "SHOW" -> "显示"; "SCAN" -> "扫描"; else -> "代码" }),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            when (mode) {
                "SHOW" -> {
                    PairingQrCode(invite.token)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { clipboard.setText(AnnotatedString(invite.token)) },
                        color = Color.White.copy(alpha = .62f),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(invite.shortCode, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(9.dp))
                            Icon(Icons.Outlined.ContentCopy, ui("Copy invite code", "复制邀请码"), modifier = Modifier.size(17.dp))
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(ui("EXPIRES IN 15 MINUTES", "15 分钟后过期"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
                    TextButton(onClick = { invite = onCreateInvite() }) {
                        Text(ui("REFRESH INVITE", "刷新邀请码"))
                    }
                }
                "SCAN" -> PairingQrScanner { scannedToken ->
                    enteredToken = scannedToken
                    mode = "CODE"
                }
                else -> OutlinedTextField(
                    value = enteredToken,
                    onValueChange = { enteredToken = it.trim().take(4096) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(ui("SIGNED INVITE", "签名邀请码")) },
                    placeholder = { Text("PC1.…") },
                    minLines = 4,
                    maxLines = 7,
                    shape = RoundedCornerShape(15.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(ui("LOCAL DISPLAY NAME", "本地显示名称")) },
                placeholder = { Text("Avery") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (mode == "CODE" && enteredToken.isNotBlank()) onAcceptInvite(enteredToken, name)
                }),
                shape = RoundedCornerShape(15.dp),
            )
            Spacer(Modifier.height(14.dp))
            if (mode == "CODE") {
                PrimaryPillButton(
                    ui("VERIFY AND ADD CONTACT", "验证并添加联系人"),
                    { onAcceptInvite(enteredToken, name) },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    ui("The signature and expiry are checked on this device before the contact is stored.", "联系人保存前，会在本机验证签名和有效期。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PairingQrCode(content: String) {
    val matrix = remember(content) {
        MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 33, 33)
    }
    Surface(modifier = Modifier.size(154.dp), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Canvas(Modifier.padding(15.dp)) {
            val cell = size.width / matrix.width
            repeat(matrix.width) { x ->
                repeat(matrix.height) { y ->
                    if (matrix[x, y]) {
                        drawRect(Ink, Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell, cell))
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingPlaceholder(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(45.dp), tint = Sage)
        Spacer(Modifier.height(13.dp))
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MutedInk, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailSheet(
    contact: TrustedContact,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onOpenOffline: () -> Unit,
    onExport: () -> Unit,
    onConnectionSettings: () -> Unit,
    onRename: (String) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
) {
    var showSafetyCode by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showBlockConfirm by rememberSaveable { mutableStateOf(false) }
    var renameDraft by remember(contact.displayName) { mutableStateOf(contact.displayName) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF4F1EB)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(50.dp), CircleShape, color = Sage.copy(alpha = .18f)) {
                    Box(contentAlignment = Alignment.Center) { Text(contact.initials, style = MaterialTheme.typography.titleMedium) }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(contact.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(ui(if (contact.verified) "SAFETY CODE VERIFIED" else "SAFETY CODE NOT VERIFIED", if (contact.verified) "安全码已验证" else "安全码未验证"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
                }
            }
            Spacer(Modifier.height(22.dp))
            PrimaryPillButton(
                when {
                    contact.readOnly -> ui("OPEN RECOVERED CANVAS", "打开已恢复画布")
                    contact.blocked -> ui("CONTACT BLOCKED", "联系人已屏蔽")
                    else -> ui("CONNECT LIVE", "实时连接")
                },
                if (contact.readOnly) onOpenOffline else onConnect,
                Modifier.fillMaxWidth(),
                enabled = !contact.blocked,
            )
            Spacer(Modifier.height(10.dp))
            if (!contact.readOnly) {
                SheetAction(Icons.Outlined.CloudOff, ui("OPEN CANVAS OFFLINE", "离线打开画布"), ui("Edits stay queued on this device", "修改将保存在本设备等待同步"), onOpenOffline)
            }
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Edit,
                ui("RENAME CONTACT", "修改联系人名称"),
                ui("This name is stored only on this device", "名称仅保存在本设备"),
                { showRename = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.PushPin,
                ui(if (contact.pinned) "UNPIN CONTACT" else "PIN CONTACT", if (contact.pinned) "取消置顶联系人" else "置顶联系人"),
                ui(if (contact.pinned) "Return to normal contact order" else "Keep this contact at the top", if (contact.pinned) "恢复普通联系人顺序" else "将此联系人固定在列表顶部"),
                { onSetPinned(!contact.pinned) },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Archive,
                ui("EXPORT THIS SPACE", "导出此空间"),
                ui("Encrypted archive, PDF or image", "加密归档、PDF 或图片"),
                onExport,
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.VerifiedUser,
                ui(if (contact.verified) "VIEW SAFETY CODE" else "VERIFY SAFETY CODE", if (contact.verified) "查看安全码" else "验证安全码"),
                ui("Compare through another trusted channel", "通过另一个可信渠道核对"),
                { showSafetyCode = true },
                enabled = contact.safetyCode.isNotBlank(),
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Key,
                ui("CONNECTION SETTINGS", "连接设置"),
                ui("Review or change the private server", "查看或修改私人服务器"),
                onConnectionSettings,
                enabled = !contact.readOnly,
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Block,
                ui(if (contact.blocked) "UNBLOCK CONTACT" else "BLOCK CONTACT", if (contact.blocked) "取消屏蔽联系人" else "屏蔽联系人"),
                ui(
                    if (contact.blocked) "Allow future connections again" else "Stop joining this contact's private space",
                    if (contact.blocked) "重新允许后续连接" else "停止加入此联系人的私人空间",
                ),
                { showBlockConfirm = true },
                enabled = !contact.readOnly,
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.Outlined.DeleteOutline, ui("DELETE CONTACT", "删除联系人"), ui("Remove this contact and its local canvas", "删除联系人及其本地画布"), onDelete)
        }
    }
    if (showSafetyCode) {
        AlertDialog(
            onDismissRequest = { showSafetyCode = false },
            icon = { Icon(Icons.Outlined.VerifiedUser, null) },
            title = { Text(ui("SAFETY CODE", "安全码")) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(contact.safetyCode, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ui("Compare all digits with this contact using another trusted channel.", "请通过另一个可信渠道，与联系人逐位核对全部数字。"),
                        color = MutedInk,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onVerify()
                    showSafetyCode = false
                }) { Text(ui(if (contact.verified) "DONE" else "CODES MATCH", if (contact.verified) "完成" else "号码一致")) }
            },
            dismissButton = { TextButton(onClick = { showSafetyCode = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(ui("RENAME CONTACT", "修改联系人名称")) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(32) },
                    singleLine = true,
                    label = { Text(ui("LOCAL CONTACT NAME", "本地联系人名称")) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameDraft.isNotBlank(),
                    onClick = {
                        onRename(renameDraft)
                        showRename = false
                    },
                ) { Text(ui("SAVE", "保存")) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            icon = { Icon(Icons.Outlined.Block, null) },
            title = {
                Text(ui(if (contact.blocked) "UNBLOCK CONTACT?" else "BLOCK CONTACT?", if (contact.blocked) "取消屏蔽联系人？" else "屏蔽联系人？"))
            },
            text = {
                Text(
                    ui(
                        if (contact.blocked) "This contact can request live sessions again." else "The local canvas stays available, but live requests and synchronization stop until you unblock this contact.",
                        if (contact.blocked) "该联系人将可以再次发起实时会话。" else "本地画布会保留，但在取消屏蔽前将停止实时请求与同步。",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetBlocked(!contact.blocked)
                    showBlockConfirm = false
                }) { Text(ui(if (contact.blocked) "UNBLOCK" else "BLOCK", if (contact.blocked) "取消屏蔽" else "屏蔽")) }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    theme: CanvasTheme,
    language: UiLanguage,
    textSize: UiTextSize,
    highContrast: Boolean,
    reduceMotion: Boolean,
    connectionRequestTimeoutSeconds: Int,
    contactSortMode: ContactSortMode,
    localDisplayName: String,
    appLockEnabled: Boolean,
    blockScreenshots: Boolean,
    genericNotificationsEnabled: Boolean,
    diagnostics: DiagnosticsSnapshot,
    serverUrl: String,
    previousServerUrl: String?,
    onThemeChange: (CanvasTheme) -> Unit,
    onLanguageChange: (UiLanguage) -> Unit,
    onTextSizeChange: (UiTextSize) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onConnectionRequestTimeoutChange: (Int) -> Unit,
    onContactSortModeChange: (ContactSortMode) -> Unit,
    onLocalDisplayNameChange: (String) -> Unit,
    onRemoveAllLocalContent: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onBlockScreenshotsChange: (Boolean) -> Unit,
    onGenericNotificationsChange: (Boolean) -> Unit,
    onRestoreArchiveClick: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onRollbackServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showNameEditor by rememberSaveable { mutableStateOf(false) }
    var nameDraft by remember(localDisplayName) { mutableStateOf(localDisplayName) }
    var showServerEditor by rememberSaveable { mutableStateOf(false) }
    var showRollbackConfirm by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showLocalData by rememberSaveable { mutableStateOf(false) }
    var showRemoveAllConfirm by rememberSaveable { mutableStateOf(false) }
    var showTextSize by rememberSaveable { mutableStateOf(false) }
    var showConnectionTimeout by rememberSaveable { mutableStateOf(false) }
    var showContactSort by rememberSaveable { mutableStateOf(false) }
    var serverDraft by remember(serverUrl) { mutableStateOf(serverUrl) }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onGenericNotificationsChange(granted)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF4F1EB)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
        ) {
            Text(language.text("APPEARANCE & PRIVACY", "外观与隐私"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(5.dp))
            Text(language.text("LOCAL PREFERENCES", "本机偏好设置"), style = MaterialTheme.typography.labelSmall, color = MutedInk)
            Spacer(Modifier.height(22.dp))
            SheetAction(
                Icons.Outlined.Edit,
                language.text("YOUR DISPLAY NAME", "你的显示名称"),
                localDisplayName,
                { showNameEditor = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Archive,
                language.text("RESTORE ARCHIVE", "恢复归档"),
                language.text("Open a password-protected .pcanvas file", "打开密码保护的 .pcanvas 文件"),
                onRestoreArchiveClick,
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Key,
                language.text("MIGRATE SERVER", "迁移服务器"),
                serverUrl,
                { showServerEditor = true },
            )
            Spacer(Modifier.height(8.dp))
            if (previousServerUrl != null) {
                SheetAction(
                    Icons.Outlined.CloudOff,
                    language.text("ROLL BACK SERVER", "回退服务器"),
                    previousServerUrl,
                    { showRollbackConfirm = true },
                )
                Spacer(Modifier.height(8.dp))
            }
            SheetAction(
                Icons.Outlined.Settings,
                language.text("DIAGNOSTICS", "诊断信息"),
                language.text("Status only — no message content", "仅含状态，不包含消息正文"),
                { showDiagnostics = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Storage,
                language.text("LOCAL DATA", "本地数据"),
                language.text("ABOUT ${readableBytes(diagnostics.approximateLocalDataBytes)} ON THIS DEVICE", "本设备约 ${readableBytes(diagnostics.approximateLocalDataBytes)}"),
                { showLocalData = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.Outlined.Palette, language.text("THEME", "主题"), if (language == UiLanguage.Chinese) when (theme) {
                CanvasTheme.MistSage -> "雾灰鼠尾草"
                CanvasTheme.MoonBlue -> "月光蓝"
                CanvasTheme.DuskViolet -> "暮色紫"
                CanvasTheme.ApricotMist -> "杏雾色"
            } else theme.label, {}, trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CanvasTheme.entries.forEach { option ->
                        Surface(
                            onClick = { onThemeChange(option) },
                            modifier = Modifier.size(27.dp),
                            shape = CircleShape,
                            color = option.accentColor().copy(alpha = .38f),
                            border = if (theme == option) androidx.compose.foundation.BorderStroke(2.dp, Ink.copy(alpha = .55f)) else null,
                        ) {}
                    }
                }
            })
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Translate,
                if (language == UiLanguage.Chinese) "界面语言" else "LANGUAGE",
                if (language == UiLanguage.Chinese) "中文 / English" else "English / 中文",
                { onLanguageChange(if (language == UiLanguage.English) UiLanguage.Chinese else UiLanguage.English) },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.FormatSize,
                language.text("TEXT SIZE", "文字大小"),
                if (language == UiLanguage.Chinese) when (textSize) {
                    UiTextSize.FollowSystem -> "跟随系统"
                    UiTextSize.Compact -> "紧凑"
                    UiTextSize.Comfortable -> "舒适"
                    UiTextSize.Large -> "大号"
                } else textSize.label,
                { showTextSize = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Contrast,
                language.text("HIGH CONTRAST", "高对比度"),
                language.text(if (highContrast) "ON" else "OFF", if (highContrast) "已开启" else "已关闭"),
                { onHighContrastChange(!highContrast) },
                trailing = { if (highContrast) Icon(Icons.Outlined.CheckCircle, null, tint = Sage) },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.MotionPhotosOff,
                language.text("REDUCE MOTION", "减少动画"),
                language.text(if (reduceMotion) "ON" else "OFF", if (reduceMotion) "已开启" else "已关闭"),
                { onReduceMotionChange(!reduceMotion) },
                trailing = { if (reduceMotion) Icon(Icons.Outlined.CheckCircle, null, tint = Sage) },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Timer,
                language.text("CONNECTION REQUEST TIMEOUT", "连接请求超时"),
                language.text("$connectionRequestTimeoutSeconds SECONDS", "$connectionRequestTimeoutSeconds 秒"),
                { showConnectionTimeout = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.PushPin,
                language.text("CONTACT ORDER", "联系人排序"),
                when (contactSortMode) {
                    ContactSortMode.PinnedFirst -> language.text("PINNED FIRST", "置顶优先")
                    ContactSortMode.Name -> language.text("NAME A–Z", "按名称")
                    ContactSortMode.RecentlyPaired -> language.text("NEWEST PAIRED", "最近配对")
                },
                { showContactSort = true },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.Outlined.Shield, language.text("BLOCK SCREENSHOTS", "阻止截屏"), language.text(if (blockScreenshots) "ON" else "OFF", if (blockScreenshots) "已开启" else "已关闭"), { onBlockScreenshotsChange(!blockScreenshots) }, trailing = {
                if (blockScreenshots) Icon(Icons.Outlined.CheckCircle, null, tint = Sage)
            })
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.VerifiedUser,
                language.text("GENERIC NOTIFICATIONS", "通用通知"),
                language.text(
                    if (genericNotificationsEnabled) "NO MESSAGE CONTENT" else "OFF",
                    if (genericNotificationsEnabled) "不显示消息正文" else "已关闭",
                ),
                {
                    if (genericNotificationsEnabled) {
                        onGenericNotificationsChange(false)
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onGenericNotificationsChange(true)
                    }
                },
                trailing = { if (genericNotificationsEnabled) Icon(Icons.Outlined.CheckCircle, null, tint = Sage) },
            )
            Spacer(Modifier.height(8.dp))
            SheetAction(
                Icons.Outlined.Lock,
                language.text("APP LOCK", "应用锁"),
                language.text(if (appLockEnabled) "DEVICE CREDENTIAL REQUIRED" else "OFF", if (appLockEnabled) "需要设备凭据" else "已关闭"),
                { onAppLockChange(!appLockEnabled) },
                trailing = { if (appLockEnabled) Icon(Icons.Outlined.CheckCircle, null, tint = Sage) },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                language.text("Local canvas data and this installation's identity keys are protected by Android Keystore.", "本地画布数据与本机身份密钥均受 Android 密钥库保护。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
        }
    }
    if (showNameEditor) {
        AlertDialog(
            onDismissRequest = { showNameEditor = false },
            title = { Text(ui("EDIT DISPLAY NAME", "修改显示名称")) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(32) },
                    singleLine = true,
                    label = { Text(ui("YOUR DISPLAY NAME", "你的显示名称")) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameDraft.isNotBlank(),
                    onClick = {
                        onLocalDisplayNameChange(nameDraft)
                        showNameEditor = false
                    },
                ) { Text(ui("SAVE", "保存")) }
            },
            dismissButton = { TextButton(onClick = { showNameEditor = false }) { Text(ui("CANCEL")) } },
        )
    }
    if (showTextSize) {
        AlertDialog(
            onDismissRequest = { showTextSize = false },
            title = { Text(ui("TEXT SIZE", "文字大小")) },
            text = {
                Column {
                    UiTextSize.entries.forEach { option ->
                        val label = if (language == UiLanguage.Chinese) when (option) {
                            UiTextSize.FollowSystem -> "跟随系统"
                            UiTextSize.Compact -> "紧凑"
                            UiTextSize.Comfortable -> "舒适"
                            UiTextSize.Large -> "大号"
                        } else option.label
                        TextButton(
                            onClick = { onTextSizeChange(option); showTextSize = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text((if (option == textSize) "✓  " else "   ") + label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTextSize = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
    if (showConnectionTimeout) {
        AlertDialog(
            onDismissRequest = { showConnectionTimeout = false },
            title = { Text(ui("CONNECTION REQUEST TIMEOUT", "连接请求超时")) },
            text = {
                Column {
                    listOf(15, 30, 60).forEach { seconds ->
                        TextButton(
                            onClick = { onConnectionRequestTimeoutChange(seconds); showConnectionTimeout = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (seconds == connectionRequestTimeoutSeconds) "✓  " else "   ") + language.text("$seconds SECONDS", "$seconds 秒"),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showConnectionTimeout = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
    if (showContactSort) {
        AlertDialog(
            onDismissRequest = { showContactSort = false },
            title = { Text(ui("CONTACT ORDER", "联系人排序")) },
            text = {
                Column {
                    ContactSortMode.entries.forEach { option ->
                        val label = when (option) {
                            ContactSortMode.PinnedFirst -> language.text("PINNED FIRST", "置顶优先")
                            ContactSortMode.Name -> language.text("NAME A–Z", "按名称")
                            ContactSortMode.RecentlyPaired -> language.text("NEWEST PAIRED", "最近配对")
                        }
                        TextButton(
                            onClick = { onContactSortModeChange(option); showContactSort = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (option == contactSortMode) "✓  " else "   ") + label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showContactSort = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
    if (showServerEditor) {
        AlertDialog(
            onDismissRequest = { showServerEditor = false },
            title = { Text(ui("MIGRATE SERVER", "迁移服务器")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = serverDraft,
                        onValueChange = { serverDraft = it.take(512) },
                        singleLine = true,
                        label = { Text("WebSocket URL") },
                        placeholder = { Text("wss://example.com/v1/ws") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ui(
                            "The new server is tested first. Existing encrypted events are then replayed; the old address remains available for rollback. Use ws:// only on a trusted local network.",
                            "将先测试新服务器，再重新上传现有加密事件；旧地址会保留用于回退。仅在可信局域网中使用 ws://。",
                        ),
                        color = MutedInk,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = serverDraft.startsWith("ws://") || serverDraft.startsWith("wss://"),
                    onClick = { onServerUrlChange(serverDraft); showServerEditor = false },
                ) { Text(ui("TEST & MIGRATE", "测试并迁移")) }
            },
            dismissButton = { TextButton(onClick = { showServerEditor = false }) { Text(ui("CANCEL", "取消")) } },
        )
    }
    if (showRollbackConfirm && previousServerUrl != null) {
        AlertDialog(
            onDismissRequest = { showRollbackConfirm = false },
            title = { Text(language.text("ROLL BACK SERVER?", "回退服务器？")) },
            text = {
                Text(
                    language.text(
                        "Encrypted history will be replayed to the previous server address.",
                        "加密历史会重新上传到上一个服务器地址。",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showRollbackConfirm = false; onRollbackServer() }) {
                    Text(language.text("ROLL BACK", "回退"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRollbackConfirm = false }) { Text(language.text("CANCEL", "取消")) }
            },
        )
    }
    if (showDiagnostics) {
        val clipboard = LocalClipboardManager.current
        val lastSync = diagnostics.lastSuccessfulSyncEpochMs.takeIf { it > 0L }
            ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(it)) }
            ?: language.text("Never", "从未")
        val report = language.text(
            "ONaline ${diagnostics.versionName}\nContacts: ${diagnostics.contactCount}\nWritable spaces: ${diagnostics.writableSpaceCount}\nApprox. local content: ${readableBytes(diagnostics.approximateLocalDataBytes)}\nQueued encrypted events: ${diagnostics.pendingEncryptedEventCount}\nLast successful sync: $lastSync\nServer: ${diagnostics.serverUrl}",
            "ONaline ${diagnostics.versionName}\n联系人：${diagnostics.contactCount}\n可写空间：${diagnostics.writableSpaceCount}\n本地内容约：${readableBytes(diagnostics.approximateLocalDataBytes)}\n待同步加密事件：${diagnostics.pendingEncryptedEventCount}\n最近成功同步：$lastSync\n服务器：${diagnostics.serverUrl}",
        )
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text(language.text("DIAGNOSTICS", "诊断信息")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(report)
                    Text(
                        language.text("This report excludes block text, emoji, keys and safety codes.", "该报告不包含气泡文字、Emoji、密钥或安全码。"),
                        color = MutedInk,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                    Text(language.text("COPY", "复制"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text(language.text("CLOSE", "关闭")) }
            },
        )
    }
    if (showLocalData) {
        AlertDialog(
            onDismissRequest = { showLocalData = false },
            title = { Text(language.text("LOCAL DATA", "本地数据")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(language.text("Stored on this device", "存储在本设备上"), style = MaterialTheme.typography.labelLarge)
                    Text(language.text("Contacts: ${diagnostics.contactCount}", "联系人：${diagnostics.contactCount}"))
                    Text(language.text("Text bubbles: ${diagnostics.blockCount}", "文字气泡：${diagnostics.blockCount}"))
                    Text(language.text("Dashed frames: ${diagnostics.frameCount}", "虚线框：${diagnostics.frameCount}"))
                    Text(language.text("Private drafts: ${diagnostics.draftCount}", "私密草稿：${diagnostics.draftCount}"))
                    Text(language.text("History events: ${diagnostics.eventCount}", "历史事件：${diagnostics.eventCount}"))
                    Text(language.text("Approximate size: ${readableBytes(diagnostics.approximateLocalDataBytes)}", "估算大小：${readableBytes(diagnostics.approximateLocalDataBytes)}"))
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        language.text(
                            "Removing local data deletes every contact and canvas from this installation. It does not erase data already stored on another device or relay.",
                            "清理本地数据会删除本机上的全部联系人和画布，但不会删除另一台设备或 Relay 中已有的数据。",
                        ),
                        color = MutedInk,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLocalData = false; showRemoveAllConfirm = true }) {
                    Text(language.text("REMOVE LOCAL DATA", "清理本地数据"), color = MutedCoral)
                }
            },
            dismissButton = { TextButton(onClick = { showLocalData = false }) { Text(language.text("CLOSE", "关闭")) } },
        )
    }
    if (showRemoveAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveAllConfirm = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MutedCoral) },
            title = { Text(language.text("REMOVE ALL LOCAL CONTACTS AND CANVASES?", "删除本机全部联系人和画布？")) },
            text = {
                Text(
                    language.text(
                        "This cannot be undone on this device. Your display name, appearance and server settings stay unchanged.",
                        "此操作在本机无法撤销。你的显示名称、外观与服务器设置会保留。",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showRemoveAllConfirm = false; onRemoveAllLocalContent(); onDismiss() }) {
                    Text(language.text("REMOVE", "删除"), color = MutedCoral)
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveAllConfirm = false }) { Text(language.text("CANCEL", "取消")) } },
        )
    }
}

private fun readableBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

private fun statusColor(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Available, ConnectionStatus.Connected -> Sage
    ConnectionStatus.Requesting -> MutedBlue
    ConnectionStatus.Offline -> MutedInk
    ConnectionStatus.Busy -> MutedCoral
}
