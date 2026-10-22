package app.meebook.launcher.data.books

import java.io.File

/**
 * A book file found under the home dir.
 * [addedAt] is the file's lastModified in ms. [folderSeries] is the parent folder name when the
 * parent is not the home dir and holds at least two books; otherwise null.
 */
data class BookFile(val file: File, val folderSeries: String?, val addedAt: Long)

object BookScanner {
    private val EXTENSIONS = listOf(".epub", ".fb2.zip", ".fb2", ".mobi", ".azw3", ".pdf", ".djvu", ".txt")

    fun isBook(name: String): Boolean {
        val n = name.lowercase()
        return EXTENSIONS.any { n.endsWith(it) }
    }

    fun scan(homeDir: File): List<BookFile> {
        val found = ArrayList<File>()
        walk(homeDir, found)
        val perDir = found.groupingBy { it.parentFile }.eachCount()
        return found.map { f ->
            val parent = f.parentFile
            val series = if (parent != null && parent != homeDir && (perDir[parent] ?: 0) >= 2) parent.name else null
            BookFile(f, series, f.lastModified())
        }.sortedBy { it.file.path }
    }

    private fun walk(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            val name = c.name
            if (name.startsWith(".")) continue
            if (c.isDirectory) {
                if (name.endsWith(".sdr", ignoreCase = true)) continue
                walk(c, out)
            } else if (c.isFile && isBook(name)) {
                out.add(c)
            }
        }
    }
}
