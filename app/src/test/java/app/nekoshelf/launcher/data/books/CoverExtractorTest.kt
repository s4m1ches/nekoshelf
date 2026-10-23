package app.nekoshelf.launcher.data.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun png(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun zip(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return file
    }

    private val container = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun opf(manifest: String, meta: String = "") = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$meta</metadata>
<manifest>$manifest</manifest></package>"""

    private fun epub(name: String, opf: String, extra: Map<String, ByteArray>): File =
        zip(tmp.newFile(name), mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
        ) + extra)

    private fun height(f: File) = BitmapFactory.decodeFile(f.path).height

    @Test fun epub3CoverImageProperty() {
        val e = epub("a.epub", opf(
            """<item id="x" href="images/other.png" media-type="image/png"/>
               <item id="c" href="images/my%20cover.png" media-type="image/png" properties="cover-image"/>"""),
            mapOf("OEBPS/images/my cover.png" to png(200, 300), "OEBPS/images/other.png" to png(10, 10)))
        val out = CoverExtractor(tmp.newFolder("cache")).cover(e, 100)
        assertNotNull(out)
        assertTrue(height(out!!) <= 100)
        assertEquals(75, height(out))
    }

    @Test fun epub2MetaCover() {
        val e = epub("b.epub", opf(
            """<item id="i1" href="a.png" media-type="image/png"/><item id="cv" href="b.png" media-type="image/png"/>""",
            """<meta name="cover" content="cv"/>"""),
            mapOf("OEBPS/a.png" to png(10, 10), "OEBPS/b.png" to png(20, 40)))
        val out = CoverExtractor(tmp.newFolder("cache")).cover(e, 1000)!!
        assertEquals(40, height(out))
    }

    @Test fun epubFirstImageFallback() {
        val e = epub("c.epub", opf(
            """<item id="t" href="t.xhtml" media-type="application/xhtml+xml"/><item id="i" href="p.png" media-type="image/png"/>"""),
            mapOf("OEBPS/p.png" to png(8, 12)))
        assertEquals(12, height(CoverExtractor(tmp.newFolder("cache")).cover(e, 1000)!!))
    }

    @Test fun epubWithoutImagesIsNull() {
        val e = epub("d.epub", opf("""<item id="t" href="t.xhtml" media-type="application/xhtml+xml"/>"""),
            mapOf("OEBPS/t.xhtml" to "<html/>".toByteArray()))
        assertNull(CoverExtractor(tmp.newFolder("cache")).cover(e, 100))
    }

    @Test fun truncatedZipIsNullNoThrow() {
        val good = epub("e.epub", opf("""<item id="i" href="p.png" media-type="image/png"/>"""),
            mapOf("OEBPS/p.png" to png(200, 300)))
        val bytes = good.readBytes()
        val bad = tmp.newFile("bad.epub").also { it.writeBytes(bytes.copyOf(bytes.size / 2)) }
        assertNull(CoverExtractor(tmp.newFolder("cache")).cover(bad, 100))
        val garbage = tmp.newFile("g.epub").also { it.writeText("not a zip") }
        assertNull(CoverExtractor(tmp.newFolder("cache2")).cover(garbage, 100))
    }

    private fun fb2(prefix: String) = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:$prefix="http://www.w3.org/1999/xlink">
<description><title-info><coverpage><image $prefix:href="#cov.png"/></coverpage></title-info></description>
<body><section><p>hi</p></section></body>
<binary id="other" content-type="image/png">AAAA</binary>
<binary id="cov.png" content-type="image/png">${Base64.getMimeEncoder().encodeToString(png(30, 60))}</binary>
</FictionBook>"""

    @Test fun fb2WithBothPrefixes() {
        for (p in listOf("l", "xlink")) {
            val f = tmp.newFile("$p.fb2").also { it.writeText(fb2(p)) }
            val out = CoverExtractor(tmp.newFolder("cache-$p")).cover(f, 1000)
            assertEquals(60, height(out!!))
        }
    }

    @Test fun fb2Zip() {
        val f = zip(tmp.newFile("book.fb2.zip"), mapOf("book.fb2" to fb2("l").toByteArray()))
        assertEquals(60, height(CoverExtractor(tmp.newFolder("cache")).cover(f, 1000)!!))
    }

    @Test fun unsupportedFormatIsNull() {
        val f = tmp.newFile("x.pdf").also { it.writeText("%PDF") }
        assertNull(CoverExtractor(tmp.newFolder("cache")).cover(f, 100))
    }

    @Test fun cachedSecondCallDoesNotReadBook() {
        val e = epub("f.epub", opf("""<item id="i" href="p.png" media-type="image/png" properties="cover-image"/>"""),
            mapOf("OEBPS/p.png" to png(20, 30)))
        val cache = tmp.newFolder("cache")
        val ex = CoverExtractor(cache)
        val first = ex.cover(e, 100)!!
        val mtime = e.lastModified()
        e.writeBytes(byteArrayOf(1, 2, 3)) // corrupt content, then restore mtime: key unchanged
        e.setLastModified(mtime)
        val second = ex.cover(e, 100)
        assertEquals(first.path, second!!.path)
    }
}
