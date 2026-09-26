package space.privatecanvas.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingSecurityInstrumentedTest {
    @Test
    fun signedInviteRoundTripsAndTamperingFails() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val identity = DeviceIdentityManager(context)
        val generated = identity.createInvite("测试用户", "ws://127.0.0.1:9876/v1/ws")
        val result = PairingInviteCodec.decodeAndVerify(generated.token)
        assertTrue(result is PairingInviteResult.Valid)
        assertEquals("测试用户", (result as PairingInviteResult.Valid).invite.inviterDisplayName)

        val finalCharacter = generated.token.last()
        val tampered = generated.token.dropLast(1) + if (finalCharacter == 'A') 'B' else 'A'
        assertTrue(PairingInviteCodec.decodeAndVerify(tampered) is PairingInviteResult.Invalid)
    }

    @Test
    fun safetyCodeIsSymmetricAndStable() {
        val first = PairingInviteCodec.safetyCode("alpha", "beta", "space")
        val second = PairingInviteCodec.safetyCode("beta", "alpha", "space")
        assertEquals(first, second)
        assertTrue(first.matches(Regex("\\d{4} \\d{4} \\d{4}")))
    }
}
