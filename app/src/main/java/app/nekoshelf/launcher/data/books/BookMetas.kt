package app.nekoshelf.launcher.data.books

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [BookMetaReader] results kept in [cacheFile] across process starts, misses included: a book
 * is opened once per (path, lastModified), not on every cold start. [resolve] returns the titles
 * for one home load and rewrites the file when it learned something new or a book left the list.
 * Blocking — call off-main. Not thread-safe; the loader calls it from one load at a time.
 */
class BookMetas(private val cacheFile: File) {
    private var memo: MutableMap<String, BookMeta?>? = null

    fun resolve(books: List<File>): Map<File, BookMeta?> {
        val known = memo ?: load().also { memo = it }
        val keys = books.associateWith(::key)
        var changed = known.keys.size != keys.size || !known.keys.containsAll(keys.values)
        val out = HashMap<File, BookMeta?>()
        for ((book, k) in keys) {
            val m = if (known.containsKey(k)) known[k] else BookMetaReader.read(book).also { known[k] = it; changed = true }
            out[book] = m
        }
        if (changed) {
            known.keys.retainAll(keys.values.toSet())
            save(known)
        }
        return out
    }

    private fun key(book: File) = "${book.path}|${book.lastModified()}"

    private fun load(): MutableMap<String, BookMeta?> = try {
        val o = JSONObject(cacheFile.readText())
        val m = HashMap<String, BookMeta?>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k)
            m[k] = a?.let { BookMeta(it.optString(0).ifEmpty { null }, it.optString(1).ifEmpty { null }) }
        }
        m
    } catch (_: Exception) {
        HashMap()
    }

    private fun save(m: Map<String, BookMeta?>) {
        try {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, if (v == null) JSONObject.NULL else JSONArray().put(v.title.orEmpty()).put(v.authors.orEmpty()))
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(cacheFile)) tmp.delete()
        } catch (_: Exception) {
            // A cache that cannot be written only costs a re-read next start.
        }
    }
}
