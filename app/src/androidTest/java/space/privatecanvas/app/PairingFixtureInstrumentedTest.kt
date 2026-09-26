package space.privatecanvas.app

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit two-emulator fixture. The token is short lived and emitted only by
 * the separately installed androidTest APK; production builds contain no such path.
 */
@RunWith(AndroidJUnit4::class)
class PairingFixtureInstrumentedTest {
    @Test
    fun createShortLivedInviteForSecondEmulator() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrivateCanvasViewModel(application)
        val invite = viewModel.createPairingInvite()
        Thread.sleep(700)
        assertTrue(PairingInviteCodec.decodeAndVerify(invite.token) is PairingInviteResult.Valid)
        Log.i("PC_PAIR_FIXTURE", invite.token)
    }
}
