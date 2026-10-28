package app.nekoshelf.launcher.data.books

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Title and authors written inside a book file; several authors are joined by "\n", as in KOReader. */
data class BookMeta(val title: String?, val authors: String?)

/**
 * Reads the title and authors from an EPUB's OPF or an FB2's title-info, for books KOReader has
 * not opened yet (their sidecar has no doc_props). Text is decoded in its declared encoding when
 * it is valid there, else as UTF-8, else as Windows-1251: some EPUBs declare UTF-8 but hold
 * Windows-1251, and KOReader still shows their titles. Never throws: any failure yields null.
 */
object BookMetaReader {
    /** The FB2 description is at the top; the rest of the book is never read. */
    private const val FB2_HEAD = 256 * 1024
    private const val MAX_OPF = 1024 * 1024L
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    private val DECLARED = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([\w.:-]+)["']""")

    fun read(book: File): BookMeta? = try {
        val n = book.name.lowercase()
        when {
            n.endsWith(".epub") -> epub(book)
            n.endsWith(".fb2.zip") -> ZipFile(book).use { z ->
                val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
                e?.let { z.getInputStream(it).use(::fb2) }
            }
            n.endsWith(".fb2") -> book.inputStream().use(::fb2)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** [bytes] as text: the declared encoding if valid in it, else UTF-8 if valid, else Windows-1251. */
    internal fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = DECLARED.find(head)?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() }
        for (cs in listOfNotNull(declared, Charsets.UTF_8)) strict(bytes, cs)?.let { return it }
        return String(bytes, WINDOWS_1251)
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun epub(book: File): BookMeta? = ZipFile(book).use { z ->
        val container = z.getEntry("META-INF/container.xml")?.let { e -> z.getInputStream(e).use { it.readBytes() } }
        var opfPath = container?.let { rootfilePath(String(it, Charsets.UTF_8)) }
        if (opfPath == null || z.getEntry(opfPath) == null) {
            opfPath = z.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".opf") }?.name
        }
        val entry = opfPath?.let { z.getEntry(it) } ?: return null
        if (entry.size > MAX_OPF) return null
        val p = parser(decode(z.getInputStream(entry).use { it.readBytes() }))
        var title: String? = null
        val authors = ArrayList<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.END_TAG && local(p) == "metadata") break
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (local(p)) {
                "title" -> if (title == null) title = clean(p.nextText())
                "creator" -> clean(p.nextText())?.let(authors::add)
            }
        }
        meta(title, authors)
    }

    private fun fb2(input: InputStream): BookMeta? {
        val head = readHead(input)
        val end = indexOf(head, "</description>".toByteArray()).takeIf { it >= 0 } ?: return null
        val p = parser(decode(head.copyOf(end)))
        var inTitleInfo = false
        var title: String? = null
        val authors = ArrayList<String>()
        val name = ArrayList<String>()
        var inAuthor = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p)) {
                    "title-info" -> inTitleInfo = true
                    "author" -> if (inTitleInfo) { inAuthor = true; name.clear() }
                    "first-name", "middle-name", "last-name" -> if (inAuthor) clean(p.nextText())?.let(name::add)
                    "book-title" -> if (inTitleInfo && title == null) title = clean(p.nextText())
                }
                XmlPullParser.END_TAG -> when (local(p)) {
                    "author" -> if (inAuthor) {
                        inAuthor = false
                        if (name.isNotEmpty()) authors.add(name.joinToString(" "))
                    }
                    "title-info" -> return meta(title, authors)
                }
            }
        }
        return null
    }

    private fun meta(title: String?, authors: List<String>) = BookMeta(title, authors.takeIf { it.isNotEmpty() }?.joinToString("\n"))

    private fun parser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(StringReader(xml.substring(xml.indexOf('<').coerceAtLeast(0))))
    }

    private fun local(p: XmlPullParser) = p.name.substringAfter(':')

    private fun clean(s: String): String? = s.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }

    private fun rootfilePath(xml: String): String? {
        val p = parser(xml)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && local(p) == "rootfile") {
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeName(i).substringAfter(':') == "full-path") return Uri.decode(p.getAttributeValue(i))
                }
            }
        }
        return null
    }

    private fun readHead(input: InputStream): ByteArray {
        val buf = ByteArray(FB2_HEAD)
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return buf.copyOf(total)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
