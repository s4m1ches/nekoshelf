package app.nekoshelf.launcher.device

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import kotlin.random.Random

/**
 * Picks the header picture: a random **landscape** image (width > height) from [dir]
 * that differs from the one shown last time (remembered in [prefs]).
 *
 * Only image bounds are read (`inJustDecodeBounds`) — no pixels are decoded here, so
 * a 9000×6000 JPEG costs a header read, not 200 MB. EXIF rotation is honoured, so a
 * portrait photo stored sideways is not mistaken for a landscape one. Files that are
 * not decodable images (wrong extension, corrupt data) are skipped.
 *
 * Does file I/O: call off the main thread.
 */
class HeaderPictures(
    private val dir: File,
    private val prefs: SharedPreferences,
    private val random: Random = Random.Default,
) {
    /** The next picture to show, or null if [dir] holds no landscape image. */
    fun next(): File? {
        val candidates = landscapeImages()
        if (candidates.isEmpty()) return null
        val last = prefs.getString(KEY_LAST, null)
        val pool = candidates.filter { it.absolutePath != last }.ifEmpty { candidates }
        val pick = pool[random.nextInt(pool.size)]
        prefs.edit().putString(KEY_LAST, pick.absolutePath).apply()
        return pick
    }

    private fun landscapeImages(): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .sortedBy { it.name }
            .filter { isLandscape(it) }

    private fun isLandscape(file: File): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var w = opts.outWidth
        var h = opts.outHeight
        if (w > 0 && h > 0 && isRotatedSideways(file)) { val t = w; w = h; h = t }
        w > 0 && h > 0 && w > h
    } catch (_: Exception) {
        false
    }

    private fun isRotatedSideways(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg" && ext != "webp") return false
        return try {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private const val KEY_LAST = "header_picture_last"
    }
}
