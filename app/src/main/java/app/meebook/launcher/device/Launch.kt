package app.meebook.launcher.device

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log
import java.io.File

/** Starts other apps. Every call returns false (and logs) instead of throwing when the target is missing. */
object Launch {
    private const val TAG = "Launch"
    private val KOREADER = ComponentName("org.koreader.launcher", "org.koreader.launcher.MainActivity")
    private val MANGA_OPEN = ComponentName("app.komikku.meebook", "komikku.meebook.bridge.BridgeOpenActivity")
    private const val MANGA_ACTION = "app.komikku.meebook.action.OPEN_MANGA"

    /** Opens [file] in KOReader, which resumes at the position stored in its sidecar. */
    fun openBook(context: Context, file: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(file), mimeOf(file.name))
            .setComponent(KOREADER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // targetSdk ≥ 24 turns on StrictMode's "death on file:// exposure" for every app, which
        // would throw in startActivity. KOReader reads the path from a file:// URI directly (a
        // content:// URI would make it copy the book), so lift that one check for this call.
        val old = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        return try {
            start(context, intent)
        } finally {
            StrictMode.setVmPolicy(old)
        }
    }

    /** The bridge contract's open action: resumes the chapter, or the details page when caught up. */
    fun openManga(context: Context, mangaId: Long): Boolean = start(
        context,
        Intent(MANGA_ACTION)
            .setComponent(MANGA_OPEN)
            .putExtra("manga_id", mangaId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun openApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".epub") -> "application/epub+zip"
            n.endsWith(".fb2.zip") -> "application/x-zip-compressed-fb2"
            n.endsWith(".fb2") -> "application/x-fictionbook+xml"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".djvu") -> "image/vnd.djvu"
            n.endsWith(".mobi") -> "application/x-mobipocket-ebook"
            n.endsWith(".azw3") -> "application/vnd.amazon.ebook"
            n.endsWith(".txt") -> "text/plain"
            else -> "*/*"
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for $intent", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "not allowed: $intent", e)
        false
    }
}
