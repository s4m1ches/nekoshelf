package app.meebook.launcher.data.books

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide memo over [CoverExtractor], including misses: a book without an extractable cover
 * is opened once per (path, lastModified), not on every home refresh. Blocking — call off-main.
 */
class BookCovers(cacheDir: File, private val heightPx: Int) {
    private val extractor = CoverExtractor(cacheDir)
    private val memo = ConcurrentHashMap<String, Result>()

    private class Result(val file: File?)

    fun cover(book: File): File? {
        val key = "${book.path}|${book.lastModified()}"
        memo[key]?.let { r -> if (r.file == null || r.file.isFile) return r.file }
        val f = extractor.cover(book, heightPx)
        memo[key] = Result(f)
        return f
    }
}
