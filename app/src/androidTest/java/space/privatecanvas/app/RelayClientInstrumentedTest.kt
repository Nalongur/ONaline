package space.privatecanvas.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RelayClientInstrumentedTest {
    @Test
    fun twoAndroidClientsExchangeAuthenticatedEncryptedEnvelope() {
        val space = RelaySpace(
            UUID.randomUUID().toString(),
            Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP),
        )
        val connected = CountDownLatch(2)
        val delivered = CountDownLatch(1)
        val acked = CountDownLatch(1)
        var received = ""
        val first = RelayClient { incoming ->
            if (incoming is RelayIncoming.Connected) connected.countDown()
            if (incoming is RelayIncoming.Ack && incoming.id == "android-event") acked.countDown()
        }
        val second = RelayClient { incoming ->
            if (incoming is RelayIncoming.Connected) connected.countDown()
            if (incoming is RelayIncoming.Item && incoming.id == "android-event") {
                received = incoming.plaintext
                delivered.countDown()
            }
        }
        try {
            first.connect("ws://10.0.2.2:9876/v1/ws", "device-a", listOf(space))
            second.connect("ws://10.0.2.2:9876/v1/ws", "device-b", listOf(space))
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            first.publish(space, "android-event", "event", "secret canvas payload")
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(acked.await(5, TimeUnit.SECONDS))
            assertEquals("secret canvas payload", received)
        } finally {
            first.close()
            second.close()
        }
    }
}
