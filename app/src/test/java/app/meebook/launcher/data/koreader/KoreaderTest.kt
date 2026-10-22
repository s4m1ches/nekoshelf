package app.meebook.launcher.data.koreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KoreaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("koreader/metadata_skorb.lua")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun sidecar(book: File, ext: String, content: String) {
        val sdr = File(book.parentFile, book.name.removeSuffix(".$ext") + ".sdr")
        sdr.mkdirs()
        File(sdr, "metadata.$ext.lua").writeText(content, Charsets.UTF_8)
    }

    private fun slash(f: File) = f.path.replace("\\", "/")

    @Test fun realSidecarFixture() {
        val book = tmp.newFile("Скорбь любви меж Цзин и Вэй.epub")
        sidecar(book, "epub", fixture())
        val s = Sidecar.read(book)!!
        assertEquals(0.012571783330747, s.percent!!, 1e-12)
        assertEquals("reading", s.status)
        assertEquals("Цин Цзюнь Мо Сяо", s.authors)
        assertEquals("Скорбь любви меж Цзин и Вэй", s.title)
        assertNull(s.series)
    }

    @Test fun statusComplete() {
        val book = tmp.newFile("a.epub")
        sidecar(book, "epub", """return { ["percent_finished"] = 1, ["summary"] = { ["status"] = "complete" }, ["doc_props"] = { ["title"] = "T", ["series"] = "S", ["series_index"] = 2 } }""")
        val s = Sidecar.read(book)!!
        assertEquals("complete", s.status)
        assertEquals(1.0, s.percent!!, 0.0)
        assertEquals("T", s.title)
        assertEquals(2.0, s.seriesIndex!!, 0.0)
    }

    @Test fun naSeriesAndEmptyAreNull() {
        val book = tmp.newFile("b.epub")
        sidecar(book, "epub", """return { ["doc_props"] = { ["title"] = "", ["series"] = "N/A" }, ["stats"] = { ["series"] = "N/A" } }""")
        val s = Sidecar.read(book)!!
        assertNull(s.series)
        assertNull(s.status)
        assertNull(s.title)
    }

    @Test fun statsFallback() {
        val book = tmp.newFile("c.epub")
        sidecar(book, "epub", """return { ["stats"] = { ["title"] = "FromStats", ["authors"] = "Au", ["language"] = "ru" } }""")
        val s = Sidecar.read(book)!!
        assertEquals("FromStats", s.title)
        assertEquals("Au", s.authors)
        assertEquals("ru", s.language)
    }

    @Test fun fb2ZipUsesLastExtension() {
        val book = tmp.newFile("Book.fb2.zip")
        val sdr = File(tmp.root, "Book.fb2.sdr")
        sdr.mkdirs()
        File(sdr, "metadata.zip.lua").writeText("""return { ["percent_finished"] = 0.5 }""")
        assertEquals(0.5, Sidecar.read(book)!!.percent!!, 0.0)
    }

    @Test fun missingOrBrokenSidecarIsNull() {
        val book = tmp.newFile("d.epub")
        assertNull(Sidecar.read(book))
        sidecar(book, "epub", "return { oops")
        assertNull(Sidecar.read(book))
    }

    @Test fun historySortsDropsMissingKeepsCyrillic() {
        val root = tmp.newFolder("koreader")
        val a = tmp.newFile("Скорбь любви меж Цзин и Вэй.epub")
        val b = tmp.newFile("b.epub")
        val gone = File(tmp.root, "gone.epub")
        File(root, "history.lua").writeText(
            """return {
                [1] = { ["file"] = "${slash(b)}", ["time"] = 100 },
                [2] = { ["file"] = "${slash(gone)}", ["time"] = 300 },
                [3] = { ["file"] = "${slash(a)}", ["time"] = 200 },
            }""",
            Charsets.UTF_8,
        )
        val h = History.read(root)
        assertEquals(listOf(slash(a), slash(b)), h.map { slash(it.file) })
        assertEquals(listOf(200L, 100L), h.map { it.time })
    }

    @Test fun historyMissingFileIsEmpty() {
        assertTrue(History.read(tmp.newFolder("k")).isEmpty())
    }

    @Test fun homeDirDefaultAndConfigured() {
        val root = tmp.newFolder("kr")
        assertEquals(File("/storage/emulated/0/Books"), KoreaderPaths(root).homeDir())
        File(root, "settings.reader.lua").writeText("""return { ["home_dir"] = "/sdcard/My Books" }""")
        assertEquals(File("/sdcard/My Books"), KoreaderPaths(root).homeDir())
    }
}
