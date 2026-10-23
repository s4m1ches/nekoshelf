package app.meebook.launcher.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class KoreaderSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, rel: String, text: String = rel): File =
        File(root, rel).also { it.parentFile!!.mkdirs(); it.writeText(text) }

    private fun entries(zip: File): Set<String> =
        ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test fun snapshotHasSettingsSdrAndManifestButNoBookinfoCache() {
        val ko = tmp.newFolder("koreader")
        val home = tmp.newFolder("Books")
        file(ko, "settings/statistics.sqlite3", "db")
        file(ko, "settings/statistics.sqlite3-wal", "wal")
        file(ko, "settings/statistics.sqlite3-shm", "shm")
        file(ko, "settings/bookinfo_cache.sqlite3", "big")
        file(ko, "settings/bookinfo_cache.sqlite3-wal", "big-wal")
        file(ko, "settings/filemanager.lua")
        file(ko, "settings/filemanager.lua.old")
        file(ko, "settings/bookshelf/shelf.lua")
        file(ko, "history.lua")
        file(ko, "settings.reader.lua")
        file(ko, "defaults.custom.lua")
        file(ko, "patches/2-fix.lua")
        file(ko, "styletweaks/tweak.css")
        for (skip in listOf("cache/c.bin", "data/d.bin", "fonts/f.ttf", "plugins/p.lua", "ota/o.bin",
            "screenshots/s.png", "help/h.html", "scripts/s.sh", "clipboard/c.txt", "crash.log")) file(ko, skip)
        file(home, "A/Book.epub", "book")
        file(home, "A/Book.sdr/metadata.epub.lua", "progress")
        file(home, "A/Book.sdr/metadata.epub.lua.old", "progress-old")

        val out = File(tmp.root, "snap.zip")
        val manifest = KoreaderSnapshot.build(ko, home, out)

        assertEquals(
            setOf(
                "koreader/settings/statistics.sqlite3",
                "koreader/settings/statistics.sqlite3-wal",
                "koreader/settings/statistics.sqlite3-shm",
                "koreader/settings/filemanager.lua",
                "koreader/settings/filemanager.lua.old",
                "koreader/settings/bookshelf/shelf.lua",
                "koreader/history.lua",
                "koreader/settings.reader.lua",
                "koreader/defaults.custom.lua",
                "koreader/patches/2-fix.lua",
                "koreader/styletweaks/tweak.css",
                "books/A/Book.sdr/metadata.epub.lua",
                "books/A/Book.sdr/metadata.epub.lua.old",
                "books-manifest.json",
            ),
            entries(out),
        )
        ZipFile(out).use { z ->
            assertEquals("wal", z.getInputStream(z.getEntry("koreader/settings/statistics.sqlite3-wal")).readBytes().decodeToString())
            assertEquals("progress", z.getInputStream(z.getEntry("books/A/Book.sdr/metadata.epub.lua")).readBytes().decodeToString())
        }
        assertEquals(listOf("A/Book.epub"), manifest.map { it.path })
        assertEquals(manifest, readManifest(out))
    }

    @Test fun manifestKeepsCyrillicNestedPaths() {
        val ko = tmp.newFolder("koreader")
        val home = tmp.newFolder("Books")
        val book = file(home, "Реинкарнация безработного/Том 06 - Детство.epub", "x".repeat(42))
        book.setLastModified(1_700_000_000_000L)
        file(home, "Реинкарнация безработного/Том 06 - Детство.sdr/metadata.epub.lua", "p")

        val out = File(tmp.root, "snap.zip")
        val manifest = KoreaderSnapshot.build(ko, home, out)

        val expected = BookEntry("Реинкарнация безработного/Том 06 - Детство.epub", 42L, book.lastModified())
        assertEquals(listOf(expected), manifest)
        assertEquals(listOf(expected), readManifest(out))
        assertTrue("books/Реинкарнация безработного/Том 06 - Детство.sdr/metadata.epub.lua" in entries(out))
    }

    @Test fun sdrFoldersAreNotBooks() {
        val home = tmp.newFolder("Books")
        file(home, "top.fb2")
        file(home, "notes.txt")
        file(home, "Series/Vol 1.epub")
        file(home, "Series/Vol 1.sdr/metadata.epub.lua")
        file(home, "Series/Vol 2.SDR/upper-case-sdr.lua")
        file(home, ".hidden/secret.epub")
        file(home, "Series/.dotfile")

        val paths = scanBooks(home).map { it.path }

        assertEquals(listOf("Series/Vol 1.epub", "notes.txt", "top.fb2"), paths.sorted())
        assertFalse(paths.any { it.contains(".sdr", ignoreCase = true) })
    }
}
