package app.nekoshelf.launcher.device

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun started(): Intent {
        val i = shadowOf(app).nextStartedActivity
        assertNotNull("an activity was started", i)
        return i
    }

    @Test
    fun openBookTargetsKoreaderWithFileUriAndEpubMime() {
        val file = File("/storage/emulated/0/Books/Скорбь любви меж Цзин и Вэй.epub")
        Launch.openBook(app, file)
        val i = started()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(ComponentName("org.koreader.launcher", "org.koreader.launcher.MainActivity"), i.component)
        assertEquals(Uri.fromFile(file), i.data)
        // R2: the decoded URI path is the file's absolute path (Cyrillic + spaces round-trip).
        // A Uri path always starts with "/"; on a Windows test host absolutePath is "D:\…".
        val abs = file.absolutePath
        assertEquals(if (abs.startsWith("/")) abs else "/$abs", i.data!!.path)
        assertTrue(i.data!!.path!!.endsWith("Скорбь любви меж Цзин и Вэй.epub"))
        assertTrue(i.dataString!!.endsWith("%20%D0%B8%20%D0%92%D1%8D%D0%B9.epub"))
        assertEquals("file", i.data!!.scheme)
        assertEquals("application/epub+zip", i.type)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun openBookMimeByExtension() {
        val cases = mapOf(
            "a.fb2" to "application/x-fictionbook+xml",
            "a.FB2.ZIP" to "application/x-zip-compressed-fb2",
            "a.pdf" to "application/pdf",
            "a.djvu" to "image/vnd.djvu",
            "a.mobi" to "application/x-mobipocket-ebook",
            "a.azw3" to "application/vnd.amazon.ebook",
            "a.txt" to "text/plain",
        )
        for ((name, mime) in cases) {
            Launch.openBook(app, File("/sdcard/Books/$name"))
            assertEquals(name, mime, started().type)
        }
    }

    @Test
    fun openMangaUsesTheBridgeContractIntent() {
        Launch.openManga(app, 42L)
        val i = started()
        assertEquals(ComponentName("app.nekoshelf.manga", "komikku.nekoshelf.bridge.BridgeOpenActivity"), i.component)
        assertEquals("app.nekoshelf.manga.action.OPEN_MANGA", i.action)
        assertEquals(42L, i.getLongExtra("manga_id", -1))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
