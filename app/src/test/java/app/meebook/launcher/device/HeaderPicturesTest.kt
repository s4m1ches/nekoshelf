package app.meebook.launcher.device

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeaderPicturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("header_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun image(dir: File, name: String, w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF777777.toInt())
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(format, 90, it) }
        return f
    }

    @Test
    fun picksTheLandscapeImageOverThePortraitOne() {
        val dir = tmp.newFolder("Pictures")
        val wide = image(dir, "wide.png", 1600, 900)
        image(dir, "tall.png", 900, 1600)
        val pictures = HeaderPictures(dir, prefs)
        repeat(10) { assertEquals(wide.absolutePath, pictures.next()?.absolutePath) }
    }

    @Test
    fun emptyFolderGivesNull() {
        assertNull(HeaderPictures(tmp.newFolder("Empty"), prefs).next())
    }

    @Test
    fun missingFolderGivesNull() {
        assertNull(HeaderPictures(File(tmp.root, "nope"), prefs).next())
    }

    @Test
    fun onlyPortraitImagesGivesNull() {
        val dir = tmp.newFolder("Portrait")
        image(dir, "a.png", 900, 1600)
        image(dir, "b.jpg", 600, 800, Bitmap.CompressFormat.JPEG)
        assertNull(HeaderPictures(dir, prefs).next())
    }

    @Test
    fun textFileIsIgnored() {
        val dir = tmp.newFolder("Mixed")
        File(dir, "notes.txt").writeText("not an image, but long enough to look like data ".repeat(20))
        assertNull(HeaderPictures(dir, prefs).next())
        val wide = image(dir, "wide.jpg", 1600, 900, Bitmap.CompressFormat.JPEG)
        repeat(5) { assertEquals(wide.absolutePath, HeaderPictures(dir, prefs).next()?.absolutePath) }
    }

    @Test
    fun corruptImageWithImageExtensionIsIgnored() {
        val dir = tmp.newFolder("Corrupt")
        File(dir, "broken.jpg").writeText("definitely not a jpeg")
        assertNull(HeaderPictures(dir, prefs).next())
    }

    @Test
    fun uppercaseExtensionsAndWebpCount() {
        val dir = tmp.newFolder("Upper")
        val wide = image(dir, "WIDE.WEBP", 1200, 600, Bitmap.CompressFormat.WEBP)
        assertEquals(wide.absolutePath, HeaderPictures(dir, prefs).next()?.absolutePath)
    }

    @Test
    fun neverTheSameFileTwiceInARow() {
        val dir = tmp.newFolder("Two")
        image(dir, "one.png", 1600, 900)
        image(dir, "two.jpeg", 1200, 800, Bitmap.CompressFormat.JPEG)
        val pictures = HeaderPictures(dir, prefs)
        var last = pictures.next()
        assertNotNull(last)
        repeat(30) {
            val next = pictures.next()
            assertNotNull(next)
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun lastShownIsRememberedAcrossInstances() {
        val dir = tmp.newFolder("Persist")
        image(dir, "one.png", 1600, 900)
        image(dir, "two.png", 1600, 900)
        var last = HeaderPictures(dir, prefs).next()
        repeat(20) {
            val next = HeaderPictures(dir, prefs).next()
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun singleLandscapeImageIsReturnedEveryTime() {
        val dir = tmp.newFolder("One")
        val only = image(dir, "only.png", 1600, 900)
        val pictures = HeaderPictures(dir, prefs)
        repeat(3) { assertEquals(only.absolutePath, pictures.next()?.absolutePath) }
    }
}
