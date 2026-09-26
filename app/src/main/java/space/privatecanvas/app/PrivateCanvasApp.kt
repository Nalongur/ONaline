package space.privatecanvas.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateCanvasApp(
    viewModel: PrivateCanvasViewModel = viewModel(),
    notificationTarget: NotificationTarget? = null,
    onNotificationTargetConsumed: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val systemDensity = LocalDensity.current
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    var unlocked by rememberSaveable { mutableStateOf(!state.appLockEnabled) }
    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        unlocked = result.resultCode == Activity.RESULT_OK
    }

    DisposableEffect(state.blockScreenshots) {
        if (state.blockScreenshots) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
    DisposableEffect(lifecycleOwner, state.appLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setAppInForeground(true)
                Lifecycle.Event.ON_STOP -> {
                    viewModel.setAppInForeground(false)
                    viewModel.flushPendingTextOperations()
                    if (state.appLockEnabled) unlocked = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.appLockEnabled) {
        if (!state.appLockEnabled) {
            unlocked = true
        } else if (!keyguardManager.isDeviceSecure) {
            viewModel.setAppLockEnabled(false)
            viewModel.showNotice("SET A DEVICE SCREEN LOCK FIRST")
        } else {
            unlocked = false
            unlockLauncher.launch(
                keyguardManager.createConfirmDeviceCredentialIntent(
                    state.language.text("Unlock ONaline", "解锁 ONaline"),
                    state.language.text("Confirm your device credential", "请验证设备凭据"),
                ),
            )
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice == null) return@LaunchedEffect
        delay(if (state.lastDeleted != null) 4200 else 2200)
        viewModel.dismissNotice()
    }
    LaunchedEffect(notificationTarget?.nonce) {
        notificationTarget?.let { target ->
            viewModel.openNotificationTarget(target)
            onNotificationTargetConsumed()
        }
    }

    BackHandler(enabled = state.route == AppRoute.Canvas) {
        viewModel.endSession()
    }

    val effectiveFontScale = state.textSize.fontScale.takeIf { it > 0f } ?: systemDensity.fontScale
    CompositionLocalProvider(
        LocalUiLanguage provides state.language,
        LocalHighContrast provides state.highContrast,
        LocalDensity provides Density(systemDensity.density, effectiveFontScale),
    ) {
      Scaffold(
          containerColor = Color.Transparent,
      ) { padding ->
        Box {
          if (!state.appLockEnabled || unlocked) {
          when (state.route) {
            AppRoute.Contacts -> ContactsScreen(
                state = state,
                contentPadding = padding,
                onCreateInvite = viewModel::createPairingInvite,
                onAcceptInvite = viewModel::acceptPairingInvite,
                onConnect = viewModel::connect,
                onCancelConnection = viewModel::cancelConnection,
                onOpenOffline = viewModel::openOffline,
                onExportContact = viewModel::openExport,
                onDeleteContact = viewModel::deleteContact,
                onRenameContact = viewModel::renameContact,
                onSetContactPinned = viewModel::setContactPinned,
                onSetContactBlocked = viewModel::setContactBlocked,
                onVerifyContact = viewModel::verifyContactSafetyCode,
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
                onRestoreArchive = viewModel::restoreEncryptedArchive,
                onPreviewArchive = viewModel::previewEncryptedArchive,
                onServerMigration = viewModel::migrateServer,
                onRollbackServer = viewModel::rollbackServerMigration,
                onShowNotice = viewModel::showNotice,
            )

            AppRoute.Canvas -> CanvasScreen(
                state = state,
                contentPadding = padding,
                viewModel = viewModel,
            )
          }
          AnimatedVisibility(
              visible = state.notice != null || state.clearUndo != null,
              enter = if (state.reduceMotion) EnterTransition.None else fadeIn(),
              exit = if (state.reduceMotion) ExitTransition.None else fadeOut(),
              modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(top = padding.calculateTopPadding() + 64.dp)
                  .widthIn(max = 320.dp),
          ) {
              Surface(
                  color = Color.White.copy(alpha = .96f),
                  contentColor = Ink,
                  shape = RoundedCornerShape(14.dp),
                  shadowElevation = 3.dp,
                  border = BorderStroke(1.dp, Hairline),
              ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                          ui(state.notice ?: if (state.clearUndo != null) "SPACE CLEARED — UNDO AVAILABLE" else ""),
                          modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp).widthIn(max = 230.dp),
                          style = MaterialTheme.typography.labelMedium,
                          maxLines = 2,
                          overflow = TextOverflow.Ellipsis,
                      )
                      if (state.lastDeleted != null) {
                          TextButton(onClick = viewModel::undoDelete) { Text(ui("UNDO", "撤销"), color = Ink) }
                      } else if (state.clearUndo != null) {
                          TextButton(onClick = viewModel::undoClearForBoth) { Text(ui("UNDO", "撤销"), color = Ink) }
                      }
                  }
              }
          }
          state.contacts.firstOrNull { it.id == state.incomingConnectionContactId }?.let { requester ->
              AlertDialog(
                  onDismissRequest = viewModel::declineIncomingConnection,
                  title = { Text(state.language.text("INCOMING CONNECTION", "收到连接请求")) },
                  text = { Text(state.language.text("${requester.displayName} wants to open your shared canvas.", "${requester.displayName} 想打开你们的共享画布。")) },
                  confirmButton = {
                      TextButton(onClick = viewModel::acceptIncomingConnection) { Text(state.language.text("ACCEPT", "接受")) }
                  },
                  dismissButton = {
                      TextButton(onClick = viewModel::declineIncomingConnection) { Text(state.language.text("DECLINE", "拒绝")) }
                  },
              )
          }
          state.pendingClearRequest
              ?.takeIf { !it.initiatedLocally && it.expiresAtEpochMs > System.currentTimeMillis() }
              ?.let { request ->
                  val contact = state.contacts.firstOrNull { it.id == request.contactId }
                  AlertDialog(
                      onDismissRequest = viewModel::rejectClearRequest,
                      title = { Text(state.language.text("CLEAR THIS SPACE FOR BOTH?", "为双方清空此空间？")) },
                      text = {
                          Text(
                              state.language.text(
                                  "${contact?.displayName ?: "Your contact"} requested to clear every block and dashed frame. Nothing is removed unless you approve. A short shared undo window follows.",
                                  "${contact?.displayName ?: "联系人"} 请求清空全部气泡和虚线框。只有你批准后才会执行，随后双方都有短暂撤销时间。",
                              ),
                          )
                      },
                      confirmButton = {
                          TextButton(onClick = viewModel::approveClearRequest) { Text(state.language.text("APPROVE", "批准")) }
                      },
                      dismissButton = {
                          TextButton(onClick = viewModel::rejectClearRequest) { Text(state.language.text("REJECT", "拒绝")) }
                      },
                  )
              }
          } else {
              Surface(color = state.theme.backgroundColor(), modifier = Modifier.matchParentSize()) {
                  androidx.compose.foundation.layout.Column(
                      modifier = Modifier.padding(32.dp),
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                  ) {
                      Text(state.language.text("ONALINE IS LOCKED", "ONaline 已锁定"))
                      TextButton(onClick = {
                          unlockLauncher.launch(
                              keyguardManager.createConfirmDeviceCredentialIntent(
                                  state.language.text("Unlock ONaline", "解锁 ONaline"),
                                  state.language.text("Confirm your device credential", "请验证设备凭据"),
                              ),
                          )
                      }) { Text(state.language.text("UNLOCK", "解锁")) }
                  }
              }
          }
        }
      }
    }
}
