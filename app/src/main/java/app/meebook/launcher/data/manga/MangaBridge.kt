package app.meebook.launcher.data.manga

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.meebook.launcher.domain.DayStat
import app.meebook.launcher.domain.Manga
import java.io.InputStream
import java.time.LocalDate

/**
 * Client of the «Манга» ContentProvider (see docs/superpowers/specs/2026-10-23-manga-bridge-contract.md).
 * Never throws: a missing package, denied permission, null cursor or any provider failure yields
 * empty/null. Each query retries once after [retryDelayMs] because the provider can fail on a cold
 * start before «Манга» has wired its DI. Calls block, so keep them off the main thread.
 */
class MangaBridge(context: Context, private val retryDelayMs: Long = 300) {
    private val appContext = context.applicationContext ?: context
    private val resolver = appContext.contentResolver

    /** «Манга» is installed (whether or not this app may read it). */
    fun installed(): Boolean = try {
        appContext.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Installed and the BRIDGE signature permission is granted: the provider can be read. */
    fun available(): Boolean = installed() && try {
        appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun library(): List<Manga> = queryRows("library", ::readLibrary) ?: emptyList()

    /** Cached cover image, or null when none is cached / the bridge is unavailable. Caller closes. */
    fun cover(mangaId: Long): InputStream? = withRetry {
        resolver.openFileDescriptor(Uri.parse("content://$AUTHORITY/cover/$mangaId"), "r")
            ?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    }

    /**
     * A full «Манга» backup stream (the provider builds it synchronously, which can take seconds),
     * or null when unavailable / the provider is too old. Caller closes; keep it off the main thread.
     */
    fun backup(): InputStream? = withRetry {
        resolver.openFileDescriptor(Uri.parse("content://$AUTHORITY/backup"), "r")
            ?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    }

    fun daily(): List<DayStat> = queryRows("stats_daily") { c ->
        val day = c.getColumnIndexOrThrow("day")
        val pages = c.getColumnIndexOrThrow("pages")
        val seconds = c.getColumnIndexOrThrow("seconds")
        buildList {
            while (c.moveToNext()) {
                add(DayStat(LocalDate.parse(c.getString(day)), c.getInt(pages), c.getInt(seconds)))
            }
        }
    } ?: emptyList()

    /** True when any day came from the one-time Komikku history import (seconds known, pages 0). */
    fun hasBackfilledDays(): Boolean = queryRows("stats_daily") { c ->
        val b = c.getColumnIndexOrThrow("backfilled")
        var any = false
        while (!any && c.moveToNext()) any = c.getInt(b) != 0
        any
    } ?: false

    /** (pages_total, seconds_total); (0, 0) when unavailable. */
    fun totals(): Pair<Long, Long> = queryRows("totals") { c ->
        if (c.moveToFirst()) {
            c.getLong(c.getColumnIndexOrThrow("pages_total")) to c.getLong(c.getColumnIndexOrThrow("seconds_total"))
        } else {
            null
        }
    } ?: (0L to 0L)

    /** Registers [onChange] for any bridge change on the main looper. Caller unregisters via the resolver. */
    fun registerObserver(onChange: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            resolver.registerContentObserver(Uri.parse("content://$AUTHORITY/"), true, observer)
        } catch (_: Exception) {
            // The observer simply never fires.
        }
        return observer
    }

    private fun <T : Any> queryRows(path: String, read: (Cursor) -> T?): T? = withRetry {
        resolver.query(Uri.parse("content://$AUTHORITY/$path"), null, null, null, null)?.use(read)
    }

    /** Runs [block]; on exception or null result waits [retryDelayMs] and tries once more, then gives up. */
    private fun <T : Any> withRetry(block: () -> T?): T? {
        for (attempt in 0..1) {
            val r = try {
                block()
            } catch (_: Exception) {
                null
            }
            if (r != null) return r
            if (attempt == 0 && retryDelayMs > 0) {
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    companion object {
        const val PACKAGE = "app.komikku.meebook"
        const val AUTHORITY = "app.komikku.meebook.bridge"
        const val PERMISSION = "app.komikku.meebook.permission.BRIDGE"
    }
}

/** Maps the `library` cursor; `chapters_new` is optional (older «Манга» lacks it) and reads as 0. */
internal fun readLibrary(c: Cursor): List<Manga> {
    val id = c.getColumnIndexOrThrow("manga_id")
    val title = c.getColumnIndexOrThrow("title")
    val added = c.getColumnIndexOrThrow("date_added")
    val last = c.getColumnIndexOrThrow("last_read_at")
    val total = c.getColumnIndexOrThrow("chapters_total")
    val read = c.getColumnIndexOrThrow("chapters_read")
    val next = c.getColumnIndexOrThrow("next_chapter_name")
    val cover = c.getColumnIndexOrThrow("cover_version")
    val new = c.getColumnIndex("chapters_new")
    return buildList {
        while (c.moveToNext()) {
            add(
                Manga(
                    id = c.getLong(id),
                    title = c.getString(title).orEmpty(),
                    lastReadAt = c.getLong(last),
                    chaptersTotal = c.getInt(total),
                    chaptersRead = c.getInt(read),
                    nextChapterName = if (c.isNull(next)) null else c.getString(next),
                    dateAdded = c.getLong(added),
                    coverVersion = c.getLong(cover),
                    chaptersNew = if (new < 0) 0 else c.getInt(new),
                ),
            )
        }
    }
}
