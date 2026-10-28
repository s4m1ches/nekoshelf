package app.nekoshelf.launcher.data.books

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookMetasTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fb2(name: String, title: String) = tmp.newFile(name).apply {
        writeText("""<?xml version="1.0" encoding="utf-8"?><FictionBook><description><title-info><book-title>$title</book-title></title-info></description></FictionBook>""")
        setLastModified(1_700_000_000_000L)
    }

    @Test fun aNewProcessTakesTitlesFromTheCacheFileWithoutOpeningTheBook() {
        val cache = File(tmp.root, "cache/book_meta.json")
        val book = fb2("a.fb2", "Первое")
        val pdf = tmp.newFile("b.pdf")
        assertEquals(mapOf(book to BookMeta("Первое", null), pdf to null), BookMetas(cache).resolve(listOf(book, pdf)))

        book.writeText("<FictionBook>") // unreadable now, same lastModified: only the cache can answer
        book.setLastModified(1_700_000_000_000L)
        assertEquals(BookMeta("Первое", null), BookMetas(cache).resolve(listOf(book))[book])
    }

    @Test fun aChangedFileIsReadAgainAndGoneBooksLeaveTheCache() {
        val cache = File(tmp.root, "book_meta.json")
        val book = fb2("a.fb2", "Старое")
        val metas = BookMetas(cache)
        metas.resolve(listOf(book))
        book.writeText("""<FictionBook><description><title-info><book-title>Новое</book-title></title-info></description></FictionBook>""")
        book.setLastModified(1_800_000_000_000L)
        assertEquals("Новое", metas.resolve(listOf(book))[book]?.title)
        assertEquals(1, JSONObject(cache.readText()).length())

        metas.resolve(emptyList())
        assertEquals(0, JSONObject(cache.readText()).length())
    }

    @Test fun aBrokenCacheFileIsIgnored() {
        val cache = tmp.newFile("book_meta.json").apply { writeText("{not json") }
        val book = fb2("a.fb2", "Название")
        assertEquals("Название", BookMetas(cache).resolve(listOf(book))[book]?.title)
        assertNull(BookMetas(cache).resolve(listOf(tmp.newFile("c.txt")))[File(tmp.root, "c.txt")])
    }
}
