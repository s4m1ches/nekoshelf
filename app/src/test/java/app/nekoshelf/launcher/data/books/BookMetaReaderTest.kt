package app.nekoshelf.launcher.data.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookMetaReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cp1251 = Charset.forName("windows-1251")

    private fun zip(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return file
    }

    private val container = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun opf(meta: String) = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">$meta</metadata>
<manifest/></package>"""

    private fun epub(name: String, opf: ByteArray): File =
        zip(tmp.newFile(name), mapOf("META-INF/container.xml" to container.toByteArray(), "OEBPS/content.opf" to opf))

    @Test fun epubTitleAndAuthors() {
        val e = epub("a.epub", opf("""<dc:title>  Задача
            трёх тел </dc:title><dc:creator opf:role="aut">Лю Цысинь</dc:creator><dc:creator>Второй Автор</dc:creator>""").toByteArray())
        assertEquals(BookMeta("Задача трёх тел", "Лю Цысинь\nВторой Автор"), BookMetaReader.read(e))
    }

    @Test fun epubThatSaysUtf8ButHoldsWindows1251() {
        val e = epub("b.epub", opf("<dc:title>Троецарствие</dc:title><dc:creator> Ло Гуаньчжун</dc:creator>").toByteArray(cp1251))
        assertEquals(BookMeta("Троецарствие", "Ло Гуаньчжун"), BookMetaReader.read(e))
    }

    @Test fun epubWithoutMetadataGivesNoTitle() {
        assertEquals(BookMeta(null, null), BookMetaReader.read(epub("c.epub", opf("").toByteArray())))
    }

    private val fb2 = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
<author><first-name>Аркадий</first-name><last-name>Стругацкий</last-name></author>
<author><first-name>Борис</first-name><middle-name>Н.</middle-name><last-name>Стругацкий</last-name></author>
<book-title>Пикник на обочине</book-title></title-info>
<document-info><author><nickname>верстальщик</nickname></author></document-info></description>
<body><p>${"текст ".repeat(100_000)}</p></body></FictionBook>"""

    @Test fun fb2InItsDeclaredEncoding() {
        val f = tmp.newFile("d.fb2").apply { writeBytes(fb2.toByteArray(cp1251)) }
        assertEquals(BookMeta("Пикник на обочине", "Аркадий Стругацкий\nБорис Н. Стругацкий"), BookMetaReader.read(f))
    }

    @Test fun zippedFb2() {
        val f = zip(tmp.newFile("e.fb2.zip"), mapOf("book.fb2" to fb2.replace("windows-1251", "utf-8").toByteArray()))
        assertEquals("Пикник на обочине", BookMetaReader.read(f)?.title)
    }

    @Test fun otherFormatsAndBrokenFilesGiveNull() {
        assertNull(BookMetaReader.read(tmp.newFile("f.pdf").apply { writeText("%PDF-1.4") }))
        assertNull(BookMetaReader.read(tmp.newFile("g.epub").apply { writeText("not a zip") }))
        assertNull(BookMetaReader.read(tmp.newFile("h.fb2").apply { writeText("<FictionBook><body>") }))
    }
}
