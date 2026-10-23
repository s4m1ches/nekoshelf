package app.nekoshelf.launcher.data.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Extracts a book cover to a PNG in [cacheDir]. Results are keyed by path + lastModified (+ height);
 * a cached PNG is returned without opening the book. Never throws: any failure yields null.
 */
class CoverExtractor(private val cacheDir: File) {

    fun cover(book: File, maxHeightPx: Int): File? {
        return try {
            if (maxHeightPx <= 0) return null
            val out = File(cacheDir, "cover_${key(book)}_$maxHeightPx.png")
            if (out.isFile && out.length() > 0) return out
            val bytes = rawCover(book) ?: return null
            val bmp = decode(bytes, maxHeightPx) ?: return null
            cacheDir.mkdirs()
            val tmp = File(cacheDir, out.name + ".tmp")
            try {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bmp.recycle()
            }
            if (!tmp.renameTo(out)) { tmp.delete(); return null }
            out
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun key(book: File): String {
        val d = MessageDigest.getInstance("SHA-256").digest("${book.path}|${book.lastModified()}".toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun rawCover(book: File): ByteArray? {
        val n = book.name.lowercase()
        return when {
            n.endsWith(".epub") -> epubCover(book)
            n.endsWith(".fb2.zip") -> ZipFile(book).use { z ->
                val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
                    ?: return null
                if (e.size > MAX_FB2) return null
                z.getInputStream(e).use { fb2Cover(it) }
            }
            n.endsWith(".fb2") -> book.inputStream().buffered().use { fb2Cover(it) }
            else -> null
        }
    }

    // ---- bitmap -----------------------------------------------------------------------------

    private fun decode(bytes: ByteArray, maxHeightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / sample > maxHeightPx && sample < 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    // ---- EPUB -------------------------------------------------------------------------------

    private class Item(val id: String?, val href: String, val type: String?, val props: String?)

    private fun epubCover(book: File): ByteArray? = ZipFile(book).use { z ->
        val container = readEntry(z, "META-INF/container.xml")?.toString(Charsets.UTF_8)
        var opfPath = container?.let { rootfilePath(it) }
        if (opfPath == null || z.getEntry(opfPath) == null) {
            opfPath = z.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".opf") }?.name
        }
        if (opfPath == null) return null
        val opfXml = readEntry(z, opfPath)?.toString(Charsets.UTF_8) ?: return null

        val items = ArrayList<Item>()
        var coverId: String? = null
        val p = Xml.newPullParser()
        p.setInput(StringReader(opfXml))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.substringAfter(':')) {
                "item" -> {
                    val href = attr(p, "href") ?: continue
                    items.add(Item(attr(p, "id"), href, attr(p, "media-type"), attr(p, "properties")))
                }
                "meta" -> if (attr(p, "name") == "cover") coverId = attr(p, "content") ?: coverId
            }
        }
        fun isImage(i: Item) = i.type?.lowercase()?.startsWith("image/") == true
        val chosen = coverId?.let { id -> items.firstOrNull { it.id == id && isImage(it) } }
            ?: items.firstOrNull { it.props?.split(' ')?.contains("cover-image") == true }
            ?: items.firstOrNull { isImage(it) }
            ?: return null
        val path = resolve(opfPath, chosen.href)
        readEntry(z, path)
    }

    private fun rootfilePath(xml: String): String? {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rootfile") {
                return attr(p, "full-path")?.let { Uri.decode(it) }
            }
        }
        return null
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
        }
        return null
    }

    private fun resolve(opfPath: String, href: String): String {
        val clean = Uri.decode(href.substringBefore('#').substringBefore('?'))
        val segs = ArrayList<String>()
        if (!clean.startsWith("/")) segs.addAll(opfPath.split('/').dropLast(1))
        for (s in clean.split('/')) {
            when (s) {
                "", "." -> {}
                ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
                else -> segs.add(s)
            }
        }
        return segs.joinToString("/")
    }

    private fun readEntry(z: ZipFile, name: String): ByteArray? {
        val e = z.getEntry(name)
            ?: z.entries().asSequence().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        if (e.isDirectory || e.size > MAX_ENTRY) return null
        return z.getInputStream(e).use { readLimited(it) }
    }

    private fun readLimited(s: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---- FB2 --------------------------------------------------------------------------------

    private fun fb2Cover(input: InputStream): ByteArray? {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var inCoverpage = false
        var coverId: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            val name = if (p.eventType == XmlPullParser.START_TAG || p.eventType == XmlPullParser.END_TAG)
                p.name.substringAfter(':') else null
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (name) {
                    "coverpage" -> inCoverpage = true
                    "image" -> if (inCoverpage && coverId == null) {
                        coverId = attr(p, "href")?.removePrefix("#")
                    }
                    "binary" -> {
                        val id = attr(p, "id")
                        if (coverId != null && id == coverId) {
                            val text = p.nextText()
                            if (text.length > MAX_ENTRY) return null
                            return Base64.decode(text, Base64.DEFAULT)
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (name == "coverpage") inCoverpage = false
            }
        }
        return null
    }

    private companion object {
        const val MAX_ENTRY = 32L * 1024 * 1024
        const val MAX_FB2 = 256L * 1024 * 1024
    }
}
