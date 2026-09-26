package space.privatecanvas.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val notificationTarget = MutableStateFlow<NotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationTarget.value = intent.notificationTarget()
        enableEdgeToEdge()
        setContent {
            val target by notificationTarget.collectAsState()
            PrivateCanvasTheme {
                PrivateCanvasApp(
                    notificationTarget = target,
                    onNotificationTargetConsumed = { notificationTarget.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTarget.value = intent.notificationTarget()
    }

    private fun Intent.notificationTarget(): NotificationTarget? {
        val contactId = getStringExtra(EXTRA_CONTACT_ID)?.takeIf { it.isNotBlank() } ?: return null
        return NotificationTarget(contactId, getStringExtra(EXTRA_BLOCK_ID)?.takeIf { it.isNotBlank() })
    }

    companion object {
        const val EXTRA_CONTACT_ID = "space.privatecanvas.app.extra.CONTACT_ID"
        const val EXTRA_BLOCK_ID = "space.privatecanvas.app.extra.BLOCK_ID"
    }
}
