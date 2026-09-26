package space.privatecanvas.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual two-emulator UI fixture. It only creates an encrypted archive in
 * Downloads; production builds contain no fixture entry point.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRestoreFixtureInstrumentedTest {
    @Test
    fun createEncryptedArchiveInDownloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = SpaceExporter.encryptedArchive(
            contact = TrustedContact("fixture", "Archive fixture", spaceId = "fixture-space"),
            blocks = listOf(
                SharedTextBlock("fixture-block", "Recovered archive text 🌿", 72f, 190f, 230f),
            ),
            frames = emptyList(),
            events = emptyList(),
            password = "restore-pass".toCharArray(),
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "private-canvas-restore-fixture.pcanvas")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        context.contentResolver.openOutputStream(uri!!)?.use { it.write(archive) }
            ?: error("Unable to open fixture destination")
    }
}
