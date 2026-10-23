package app.nekoshelf.launcher.data.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun book(dir: File, name: String): File {
        dir.mkdirs()
        return File(dir, name).also { it.writeText("x") }
    }

    @Test fun folderWithSeveralBooksIsSeries() {
        val home = tmp.root
        val dir = File(home, "Пикник в потусторонье")
        repeat(3) { book(dir, "Том $it.epub") }
        val res = BookScanner.scan(home)
        assertEquals(3, res.size)
        assertTrue(res.all { it.folderSeries == "Пикник в потусторонье" })
    }

    @Test fun singleBookInRootHasNoSeries() {
        val f = book(tmp.root, "Скорбь любви меж Цзин и Вэй.epub")
        f.setLastModified(1_700_000_000_000L)
        val res = BookScanner.scan(tmp.root)
        assertEquals(1, res.size)
        assertNull(res[0].folderSeries)
        assertEquals(1_700_000_000_000L, res[0].addedAt)
    }

    @Test fun singleBookInSubfolderHasNoSeries() {
        book(File(tmp.root, "Solo"), "a.epub")
        assertNull(BookScanner.scan(tmp.root).single().folderSeries)
    }

    @Test fun sdrAndHiddenFoldersIgnored() {
        book(tmp.root, "a.epub")
        book(File(tmp.root, "a.sdr"), "metadata.epub.lua")
        book(File(tmp.root, "a.sdr"), "junk.epub")
        book(File(tmp.root, ".hidden"), "h.epub")
        val res = BookScanner.scan(tmp.root)
        assertEquals(listOf("a.epub"), res.map { it.file.name })
    }

    @Test fun extensionsFiltered() {
        for (n in listOf("a.epub", "b.fb2", "c.fb2.zip", "d.mobi", "e.azw3", "f.pdf", "g.djvu", "h.txt", "i.jpg", "j.lua", "k.zip"))
            book(tmp.root, n)
        val names = BookScanner.scan(tmp.root).map { it.file.name }.toSet()
        assertEquals(setOf("a.epub", "b.fb2", "c.fb2.zip", "d.mobi", "e.azw3", "f.pdf", "g.djvu", "h.txt"), names)
    }

    @Test fun missingHomeIsEmpty() {
        assertTrue(BookScanner.scan(File(tmp.root, "nope")).isEmpty())
    }
}
