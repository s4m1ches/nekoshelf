package app.meebook.launcher.backup

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class RestorePlanTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var ko: File
    private lateinit var home: File
    private val noDownload: (BookEntry, File) -> Unit = { b, _ -> error("unexpected download of ${b.path}") }

    private fun file(root: File, rel: String, text: String = rel): File =
        File(root, rel).also { it.parentFile!!.mkdirs(); it.writeText(text) }

    private fun statsDb(f: File, rows: Int) {
        f.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE page_stat_data (id_book integer, page integer NOT NULL DEFAULT 0, start_time integer NOT NULL DEFAULT 0, duration integer NOT NULL DEFAULT 0, total_pages integer NOT NULL DEFAULT 0)")
        for (i in 1..rows) db.execSQL("INSERT INTO page_stat_data VALUES (1, $i, $i, 10, 100)")
        db.close()
        // Nothing else must sit next to the DB in the fixture.
        listOf("-wal", "-shm", "-journal").forEach { File(f.path + it).delete() }
    }

    private val book = "Реинкарнация безработного/Том 06 - Детство - Арка Возвращение домой.epub"
    private val sdr = "Реинкарнация безработного/Том 06 - Детство - Арка Возвращение домой.sdr"

    /** A small live reader: KOReader dir + books home, then a snapshot of it. */
    private fun liveReaderAndSnapshot(): File {
        ko = tmp.newFolder("koreader")
        home = tmp.newFolder("Books")
        statsDb(File(ko, "settings/statistics.sqlite3"), rows = 3)
        file(ko, "settings/filemanager.lua")
        file(ko, "history.lua")
        file(ko, "settings.reader.lua")
        file(home, book, "book-bytes")
        file(home, "$sdr/metadata.epub.lua", "progress 42%")
        file(home, "$sdr/metadata.epub.lua.old", "progress 41%")
        file(home, "Other.epub", "other")
        file(home, "Other.sdr/metadata.epub.lua", "other progress")
        return File(tmp.root, "snap.zip").also { KoreaderSnapshot.build(ko, home, it) }
    }

    private fun zipOf(name: String, vararg entries: Pair<String, String>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, text) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        return f
    }

    private fun entryBytes(zip: File, name: String): ByteArray =
        ZipFile(zip).use { z -> z.getInputStream(z.getEntry(name)).readBytes() }

    @Test fun nothingMissingOnLiveReaderMeansEmptyPlan() {
        val zip = liveReaderAndSnapshot()
        val plan = planRestore(zip, ko, home)
        assertEquals(RestorePlan(emptyList(), books = 0, sdr = 0, statsRestored = false), plan)
    }

    @Test fun missingBookAndSdrComeBack() {
        val zip = liveReaderAndSnapshot()
        val sdrBytes = File(home, "$sdr/metadata.epub.lua").readBytes()
        val sdrOldBytes = File(home, "$sdr/metadata.epub.lua.old").readBytes()
        File(home, book).delete()
        File(home, sdr).deleteRecursively()

        val plan = planRestore(zip, ko, home)
        assertEquals(1, plan.books)
        assertEquals(1, plan.sdr)
        assertFalse(plan.statsRestored)
        assertEquals(3, plan.items.size)

        val calls = ArrayList<BookEntry>()
        applyRestore(zip, plan) { entry, dest -> calls += entry; dest.writeText("book-bytes") }

        assertEquals(listOf(book), calls.map { it.path })
        assertEquals("book-bytes", File(home, book).readText())
        assertArrayEquals(sdrBytes, File(home, "$sdr/metadata.epub.lua").readBytes())
        assertArrayEquals(sdrOldBytes, File(home, "$sdr/metadata.epub.lua.old").readBytes())
        // No temp files left behind.
        assertEquals(setOf("metadata.epub.lua", "metadata.epub.lua.old"), File(home, sdr).list()!!.toSet())
        assertEquals(setOf(File(book).name, File(sdr).name), File(home, book).parentFile!!.list()!!.toSet())
        // Applied once, nothing is missing any more.
        assertEquals(0, planRestore(zip, ko, home).items.size)
    }

    @Test fun bookNotInManifestIsNotRestored() {
        ko = tmp.newFolder("koreader")
        home = tmp.newFolder("Books")
        file(home, "Kept.epub")
        file(home, "DeletedOnPurpose.epub").delete()
        val zip = File(tmp.root, "snap.zip").also { KoreaderSnapshot.build(ko, home, it) }
        File(home, "Kept.epub").delete()

        val plan = planRestore(zip, ko, home)

        assertEquals(1, plan.books)
        assertEquals(listOf("Kept.epub"), plan.items.mapNotNull { it.book?.path })
    }

    @Test fun existingSdrIsNeverOverwritten() {
        val zip = liveReaderAndSnapshot()
        val local = File(home, "$sdr/metadata.epub.lua")
        local.writeText("newer progress 80%")
        File(home, "$sdr/metadata.epub.lua.old").delete() // the folder exists, so nothing of it comes back
        val before = local.readBytes()

        val plan = planRestore(zip, ko, home)
        assertEquals(0, plan.sdr)
        assertTrue(plan.items.isEmpty())
        applyRestore(zip, plan, noDownload)

        assertArrayEquals(before, local.readBytes())
        assertFalse(File(home, "$sdr/metadata.epub.lua.old").exists())
    }

    @Test fun emptySdrFolderCountsAsPresent() {
        val zip = liveReaderAndSnapshot()
        File(home, sdr).deleteRecursively()
        File(home, sdr).mkdirs()
        assertEquals(0, planRestore(zip, ko, home).sdr)
    }

    @Test fun statsRestoredOnlyWhenMissingOrEmpty() {
        val zip = liveReaderAndSnapshot()
        val local = File(ko, "settings/statistics.sqlite3")
        val snapshotBytes = entryBytes(zip, "koreader/settings/statistics.sqlite3")

        // Has rows -> kept.
        statsDb(local.also { it.delete() }, rows = 1)
        assertFalse(planRestore(zip, ko, home).statsRestored)

        // Empty (0 rows) -> replaced by the snapshot's DB.
        statsDb(local.also { it.delete() }, rows = 0)
        val emptyPlan = planRestore(zip, ko, home)
        assertTrue(emptyPlan.statsRestored)
        applyRestore(zip, emptyPlan, noDownload)
        assertArrayEquals(snapshotBytes, local.readBytes())

        // Missing -> restored.
        local.delete()
        val missingPlan = planRestore(zip, ko, home)
        assertTrue(missingPlan.statsRestored)
        assertEquals(0, missingPlan.books)
        assertEquals(0, missingPlan.sdr)
        applyRestore(zip, missingPlan, noDownload)
        assertArrayEquals(snapshotBytes, local.readBytes())
    }

    @Test fun unreadableLocalStatsIsNotReplaced() {
        val zip = liveReaderAndSnapshot()
        val local = File(ko, "settings/statistics.sqlite3")
        local.writeText("not a database")
        assertFalse(planRestore(zip, ko, home).statsRestored)
        assertEquals("not a database", local.readText())
    }

    @Test fun walNeverPairsWithADifferentDb() {
        ko = tmp.newFolder("koreader")
        home = tmp.newFolder("Books")
        val zip = zipOf(
            "snap.zip",
            "koreader/settings/statistics.sqlite3" to "snap-db",
            // Stats siblings must not be in a snapshot (it is consolidated); if one is, it is ignored.
            "koreader/settings/statistics.sqlite3-wal" to "snap-wal",
            "koreader/settings/statistics.sqlite3-shm" to "snap-shm",
            "koreader/settings/vocab.sqlite3" to "vocab-db",
            "koreader/settings/vocab.sqlite3-wal" to "vocab-wal",
            "koreader/settings/vocab.sqlite3-shm" to "vocab-shm",
            "koreader/settings/orphan.sqlite3-wal" to "orphan-wal",
            "books-manifest.json" to "[]",
        )
        val stats = File(ko, "settings/statistics.sqlite3")
        val vocab = File(ko, "settings/vocab.sqlite3")

        // A local -wal left behind (KOReader running or crashed): that DB does not come back.
        file(ko, "settings/statistics.sqlite3-wal", "local-wal")
        file(ko, "settings/vocab.sqlite3-wal", "local-wal")
        val blocked = planRestore(zip, ko, home)
        assertFalse(blocked.statsRestored)
        assertTrue(blocked.items.isEmpty())
        File(ko, "settings/statistics.sqlite3-wal").delete()
        File(ko, "settings/vocab.sqlite3-wal").delete()

        // Missing DBs, nothing next to them: stats comes back as one file; another DB with its own
        // -wal; -shm and orphan -wal never.
        val plan = planRestore(zip, ko, home)
        assertTrue(plan.statsRestored)
        assertEquals(
            listOf("koreader/settings/statistics.sqlite3", "koreader/settings/vocab.sqlite3", "koreader/settings/vocab.sqlite3-wal"),
            plan.items.map { it.zipEntry },
        )
        // The vocab DB appears on the reader between planning and applying: neither it nor its -wal is written.
        vocab.writeText("fresh local db")
        applyRestore(zip, plan, noDownload)
        assertEquals("snap-db", stats.readText())
        assertEquals("fresh local db", vocab.readText())
        assertEquals(setOf("statistics.sqlite3", "vocab.sqlite3"), File(ko, "settings").list()!!.toSet())
    }

    @Test fun interruptedSdrRestoreLeavesNothingAndIsOfferedAgain() {
        val zip = liveReaderAndSnapshot()
        File(home, sdr).deleteRecursively()
        val plan = planRestore(zip, ko, home)
        assertEquals(1, plan.sdr)
        // The same plan against a snapshot that breaks after the .sdr's first file (CRC error, disk full).
        val broken = zipOf(
            "broken.zip",
            "books/$sdr/metadata.epub.lua" to "progress 42%",
            "books-manifest.json" to "[]",
        )

        assertThrows(IOException::class.java) { applyRestore(broken, plan, noDownload) }

        assertFalse(File(home, sdr).exists())
        assertEquals(setOf(File(book).name), File(home, book).parentFile!!.list()!!.toSet())
        val again = planRestore(zip, ko, home)
        assertEquals(1, again.sdr)
        applyRestore(zip, again, noDownload)
        assertEquals(setOf("metadata.epub.lua", "metadata.epub.lua.old"), File(home, sdr).list()!!.toSet())
    }

    @Test fun shortDownloadIsNotPlacedAndBookIsOfferedAgain() {
        val zip = liveReaderAndSnapshot()
        File(home, book).delete()
        val plan = planRestore(zip, ko, home)

        assertThrows(IOException::class.java) { applyRestore(zip, plan) { _, _ -> /* wrote nothing */ } }
        assertThrows(IOException::class.java) { applyRestore(zip, plan) { _, dest -> dest.writeText("book") } }

        assertFalse(File(home, book).exists())
        assertEquals(setOf(File(sdr).name), File(home, book).parentFile!!.list()!!.toSet())
        assertEquals(1, planRestore(zip, ko, home).books)
    }

    @Test fun staleTempsOfAKilledRestoreAreRemoved() {
        val zip = liveReaderAndSnapshot()
        File(home, book).delete()
        File(home, sdr).deleteRecursively()
        File(ko, "history.lua").delete()
        val bookDir = File(home, book).parentFile!!
        file(bookDir, ".restore-123.tmp", "half a book")
        file(bookDir, ".restore-${File(sdr).name}-99.tmp/metadata.epub.lua", "half an sdr")
        file(ko, ".restore-456.tmp", "half a history")
        file(bookDir, ".restore-keep.txt", "not ours: wrong suffix")
        val plan = planRestore(zip, ko, home)

        applyRestore(zip, plan) { _, dest -> dest.writeText("book-bytes") }

        assertEquals(setOf(File(book).name, File(sdr).name, ".restore-keep.txt"), bookDir.list()!!.toSet())
        assertFalse(File(ko, ".restore-456.tmp").exists())
        assertEquals("book-bytes", File(home, book).readText())
    }

    @Test fun missingKoreaderFileComesBackExistingOneIsKept() {
        val zip = liveReaderAndSnapshot()
        File(ko, "history.lua").delete()
        File(ko, "settings.reader.lua").writeText("changed today")

        val plan = planRestore(zip, ko, home)
        assertEquals(listOf("koreader/history.lua"), plan.items.map { it.zipEntry })
        assertEquals(0, plan.books)
        applyRestore(zip, plan, noDownload)

        assertEquals("history.lua", File(ko, "history.lua").readText())
        assertEquals("changed today", File(ko, "settings.reader.lua").readText())
    }

    @Test fun fileAppearingBeforeApplyIsNotOverwritten() {
        val zip = liveReaderAndSnapshot()
        File(home, book).delete()
        File(ko, "history.lua").delete()
        val plan = planRestore(zip, ko, home)
        File(ko, "history.lua").writeText("written by KOReader meanwhile")

        applyRestore(zip, plan) { _, dest ->
            dest.writeText("book-bytes")
            File(home, book).writeText("copied over USB meanwhile")
        }

        assertEquals("written by KOReader meanwhile", File(ko, "history.lua").readText())
        assertEquals("copied over USB meanwhile", File(home, book).readText())
        assertEquals(setOf(File(book).name, File(sdr).name), File(home, book).parentFile!!.list()!!.toSet())
    }

    @Test fun zipSlipEntriesAreRejected() {
        val root = tmp.newFolder("sdcard")
        ko = File(root, "koreader").also { it.mkdirs() }
        home = File(root, "Books").also { it.mkdirs() }
        val zip = zipOf(
            "evil.zip",
            "koreader/settings/../../evil.lua" to "x",
            "koreader/settings/../history.lua" to "x",
            "koreader//settings/abs.lua" to "x",
            "koreader/plugins/evil.koplugin/main.lua" to "x",
            "koreader/settings/bookinfo_cache.sqlite3" to "x",
            "books/../evil.sdr/metadata.lua" to "x",
            "books/a/../../evil.sdr/metadata.lua" to "x",
            "books//abs.sdr/metadata.lua" to "x",
            "books/not-an-sdr/file.lua" to "x",
            "books-manifest.json" to """[{"path":"../outside.epub","size":1,"mtime":1},{"path":"/abs.epub","size":1,"mtime":1},{"path":"a/./b.epub","size":1,"mtime":1},{"path":"","size":1,"mtime":1}]""",
        )

        val plan = planRestore(zip, ko, home)

        assertEquals(RestorePlan(emptyList(), books = 0, sdr = 0, statsRestored = false), plan)
        applyRestore(zip, plan, noDownload)
        assertEquals(setOf("koreader", "Books"), root.list()!!.toSet())
        assertTrue(ko.list()!!.isEmpty())
        assertTrue(home.list()!!.isEmpty())
        assertFalse(File(tmp.root, "evil.lua").exists())
    }
}
