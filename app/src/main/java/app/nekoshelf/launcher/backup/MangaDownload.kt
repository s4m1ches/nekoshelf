package app.nekoshelf.launcher.backup

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * [name] in [dir] if nothing is there, else `name (2).ext`, `name (3).ext`, … — the first free one.
 * Never an existing file: the owner's earlier saves stay as they are.
 */
fun freeDownloadTarget(dir: File, name: String): File {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val ext = name.substring(dot)
    var f = File(dir, name)
    var n = 2
    while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
    return f
}

/**
 * Fills a hidden temp file in [dir] with [fill] (e.g. a Drive download), then renames it to the
 * [freeDownloadTarget] for [name], never over an existing file. Returns the saved file; on any
 * failure nothing is left in [dir].
 */
fun saveIntoFolder(dir: File, name: String, fill: (File) -> Unit): File {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
    val tmp = File.createTempFile(".download-", ".tmp", dir)
    try {
        fill(tmp)
        repeat(20) {
            val target = freeDownloadTarget(dir, name)
            try {
                Files.move(tmp.toPath(), target.toPath()) // no REPLACE_EXISTING
                return target
            } catch (e: FileAlreadyExistsException) {
                // Appeared since the check: try the next free name.
            }
        }
        throw IOException("no free name for $name in $dir")
    } finally {
        tmp.delete()
    }
}
