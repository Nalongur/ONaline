package space.privatecanvas.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceExporterInstrumentedTest {
    private val contact = TrustedContact(id = "c1", displayName = "Avery", spaceId = "s1")
    private val blocks = listOf(SharedTextBlock("b1", "hello 🌿", 10f, 20f))

    @Test
    fun archiveContainsVersionedStrongEncryptionEnvelope() {
        val bytes = SpaceExporter.encryptedArchive(contact, blocks, emptyList(), emptyList(), "correct horse".toCharArray())
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("private-canvas-archive", root.getString("format"))
        assertEquals("PBKDF2-HMAC-SHA256", root.getString("kdf"))
        assertEquals("AES-256-GCM", root.getString("cipher"))
        assertTrue(root.getString("ciphertext").isNotBlank())
        assertTrue(!String(bytes).contains("hello"))
        val restored = SpaceExporter.decryptArchive(bytes, "correct horse".toCharArray())
        assertEquals("hello 🌿", restored.blocks.single().text)
        assertTrue(restored.blocks.single().isRecovered)
    }

    @Test
    fun pdfAndPngAreRealDocuments() {
        val pdf = SpaceExporter.pdf(contact, blocks, emptyList())
        val png = SpaceExporter.png(contact, blocks, emptyList())
        assertTrue(String(pdf.copyOfRange(0, 4), Charsets.US_ASCII).startsWith("%PDF"))
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }

    @Test
    fun pdfUsesMultipleRegionPagesForDistantCanvasContent() {
        val distantBlocks = listOf(
            SharedTextBlock("left", "left edge", -900f, 20f),
            SharedTextBlock("right", "right edge", 1900f, 20f),
        )
        val event = CanvasEvent(
            id = "event-1",
            contactId = contact.id,
            deviceId = "device-1",
            sequence = 1L,
            type = CanvasEventType.BlockTextChanged,
            objectId = "right",
            payload = "{}",
            occurredAtEpochMs = 1_700_000_000_000L,
        )
        val pdf = SpaceExporter.pdf(contact, distantBlocks, emptyList(), listOf(event), includeTimestamps = true)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "region-pages.pdf").apply { writeBytes(pdf) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount >= 2) }
        descriptor.close()
        file.delete()
    }
}
