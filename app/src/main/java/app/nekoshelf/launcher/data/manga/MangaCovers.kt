package app.nekoshelf.launcher.data.manga

import java.io.File
import java.io.InputStream

/**
 * Disk cache of «Манга» covers keyed by `id + coverVersion`, so the shelf does not re-query the
 * bridge on every refresh. Misses (no cover cached in «Манга» yet; each one costs the bridge's
 * 300 ms retry) are remembered for [missTtlMs] as empty `<key>.miss` marker files, so a process
 * restart doesn't pay them again. Blocking — call off the main thread.
 */
class MangaCovers(
    cacheDir: File,
    private val missTtlMs: Long = 6 * 60 * 60 * 1000L,
    /** The bridge's cover stream ([MangaBridge.cover]); null when «Манга» has none. */
    private val fetch: (mangaId: Long) -> InputStream?,
) {
    private val dir = File(cacheDir, "manga_covers")

    fun cover(mangaId: Long, coverVersion: Long): File? {
        // Contract: cover_version 0 means «Манга» has no cover — don't ask (each ask that finds
        // nothing costs the bridge's 300 ms retry).
        if (coverVersion == 0L) return null
        val key = "${mangaId}_$coverVersion"
        val out = File(dir, "$key.img")
        if (out.isFile && out.length() > 0) return out
        val miss = File(dir, "$key.miss")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() in 0 until missTtlMs) return null
        val stream = fetch(mangaId)
        if (stream == null) {
            markMiss(miss)
            return null
        }
        return try {
            dir.mkdirs()
            val tmp = File(dir, "$key.${System.nanoTime()}.tmp")
            stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L || !tmp.renameTo(out)) {
                tmp.delete()
                markMiss(miss)
                return null
            }
            miss.delete()
            // Drop older versions of this title's cover (and their miss markers).
            dir.listFiles { f -> f.name.startsWith("${mangaId}_") && f.name != out.name }?.forEach { it.delete() }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun markMiss(miss: File) {
        try {
            dir.mkdirs()
            miss.writeBytes(ByteArray(0))
            miss.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
            // Not remembered: the next refresh asks the bridge again.
        }
    }
}
