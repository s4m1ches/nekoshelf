package app.nekoshelf.launcher.backup

import androidx.test.core.app.ApplicationProvider
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.DriveException
import app.nekoshelf.launcher.backup.drive.RemoteFile
import app.nekoshelf.launcher.backup.drive.TreeFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class InboxRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_761_300_000_000L
    private var free = Long.MAX_VALUE
    private lateinit var drive: FakeDrive
    private lateinit var store: BackupStore
    private lateinit var home: File
    private lateinit var root: String
    private lateinit var books: String

    @Before fun setUp() {
        drive = FakeDrive()
        store = BackupStore(ApplicationProvider.getApplicationContext())
        store.save(BackupStatus.EMPTY)
        store.saveInboxSeen(emptySet())
        store.signedIn("owner@example.com", now)
        store.update { it.copy(inboxEnabled = true) }
        home = tmp.newFolder("Books")
        root = drive.folder("Nekoshelf", null)
        books = drive.folder("books", root)
    }

    private fun runner() = InboxRunner(drive, store, { home }, { free }, { now })

    private fun run() = runner().run()

    private fun userFile(parent: String, name: String, text: String = name, mime: String = "application/epub+zip", mtime: Long = 0L) =
        drive.put(parent, name, text.toByteArray(), mimeType = mime, modifiedTime = mtime)

    private fun mirrorFile(parent: String, name: String) =
        drive.put(parent, name, "mirror".toByteArray(), props = mapOf("kind" to "book", "pid" to "x"))

    private fun local(rel: String) = File(home, rel)

    private fun seenOnDrive(): Set<String> {
        val f = drive.nodes.values.single { it.parent == root && it.name == "inbox-seen.json" }
        val ids = JSONObject(String(f.bytes)).getJSONArray("ids")
        return (0 until ids.length()).map { ids.getString(it) }.toSet()
    }

    private fun tf(id: String, path: String, mime: String = "application/pdf", props: Map<String, String> = emptyMap()) =
        TreeFile(RemoteFile(id, path.substringAfterLast('/'), 1, props, mime), path)

    // ---- candidate selection (pure) ------------------------------------------------------------

    @Test fun candidatesFollowTheRules() {
        val tree = listOf(
            tf("1", "a.epub"),
            tf("2", "mirror.epub", props = mapOf("kind" to "book")),
            tf("3", "seen.epub"),
            tf("4", "doc", mime = "application/vnd.google-apps.document"),
            tf("5", "link", mime = "application/vnd.google-apps.shortcut"),
            tf("6", "sub/.hidden.epub"),
            tf("7", "Серия/Том 1.fb2"),
            tf("8", "sub", mime = "application/vnd.google-apps.folder"),
        )
        assertEquals(listOf("1", "7"), inboxCandidates(tree, setOf("3")).map { it.file.id })
    }

    // ---- runner ---------------------------------------------------------------------------------

    @Test fun appCreatedMirrorFilesAreNeverCandidates() {
        mirrorFile(books, "old.epub")
        val sub = drive.folder("sub", books)
        mirrorFile(sub, "old2.pdf")
        userFile(books, "new.epub", "NEW")

        val r = run()

        assertEquals(InboxResult(received = 1, skippedExisting = 0, error = null), r)
        assertEquals("NEW", local("new.epub").readText())
        assertFalse(local("old.epub").exists())
        assertFalse(local("sub/old2.pdf").exists())
    }

    @Test fun seenIdsAreNeverDownloadedAgain() {
        val id = userFile(books, "a.epub")
        store.saveInboxSeen(setOf(id))

        val r = run()

        assertEquals(0, r.received)
        assertFalse(local("a.epub").exists())
        assertTrue(id !in drive.downloads)
    }

    @Test fun deletedOnReaderDoesNotComeBack() {
        val id = userFile(books, "a.epub", "AAA")
        assertEquals(1, run().received)
        assertTrue(local("a.epub").delete())
        drive.downloads.clear()

        val r = run()

        assertEquals(InboxResult(0, 0, null), r)
        assertFalse(local("a.epub").exists())
        assertTrue(id !in drive.downloads)
        assertTrue(id in store.inboxSeen())
    }

    @Test fun seenSetOnDriveSurvivesWipe() {
        val id = userFile(books, "a.epub")
        run()
        assertEquals(setOf(id), seenOnDrive())
        val seenFile = drive.nodes.values.single { it.name == "inbox-seen.json" }
        assertEquals(now, JSONObject(String(seenFile.bytes)).getLong("updated"))

        // The reader is wiped: no books, no local seen set.
        local("a.epub").delete()
        store.saveInboxSeen(emptySet())
        drive.downloads.clear()

        val r = run()

        assertEquals(0, r.received)
        assertFalse(local("a.epub").exists())
        assertTrue(id !in drive.downloads)
        assertEquals(setOf(id), store.inboxSeen()) // the local cache is refilled from Drive
    }

    @Test fun seenFileOnDriveIsReplacedNotDuplicated() {
        val a = userFile(books, "a.epub")
        run()
        val b = userFile(books, "b.epub")
        now += 1000

        run()

        assertEquals(setOf(a, b), seenOnDrive()) // single{} also checks there is one file
        assertEquals(now, JSONObject(String(drive.nodes.values.single { it.name == "inbox-seen.json" }.bytes)).getLong("updated"))
        // Nothing new: the file is not rewritten.
        drive.uploads.clear()
        run()
        assertEquals(emptyList<String>(), drive.uploads)
    }

    @Test fun seenIdsFromDriveAndLocalAreMerged() {
        val a = userFile(books, "a.epub")
        val b = userFile(books, "b.epub")
        val c = userFile(books, "c.epub", "C")
        val json = File(tmp.root, "seen.json").apply { writeText("""{"ids":["$a"],"updated":1}""") }
        drive.upload(root, "inbox-seen.json", json, mapOf("kind" to "inbox-seen"), null)
        store.saveInboxSeen(setOf(b))

        val r = run()

        assertEquals(1, r.received)
        assertEquals("C", local("c.epub").readText())
        assertEquals(setOf(a, b, c), seenOnDrive())
        assertEquals(setOf(a, b, c), store.inboxSeen())
    }

    @Test fun existingLocalPathIsSkippedAndSeen() {
        local("sub").mkdirs()
        local("sub/a.epub").writeText("mine")
        val sub = drive.folder("sub", books)
        val id = userFile(sub, "a.epub", "drive version")

        val r = run()

        assertEquals(InboxResult(received = 0, skippedExisting = 1, error = null), r)
        assertEquals("mine", local("sub/a.epub").readText())
        assertTrue(id in store.inboxSeen())
        // Deleted later on the reader: still not pulled.
        local("sub/a.epub").delete()
        assertEquals(0, run().received)
        assertFalse(local("sub/a.epub").exists())
    }

    @Test fun siblingsWithTheSameNameAreKeyedById() {
        val first = userFile(books, "a.epub", "first")
        val second = userFile(books, "a.epub", "second")

        val r = run()

        assertEquals(InboxResult(received = 1, skippedExisting = 1, error = null), r)
        assertEquals("first", local("a.epub").readText())
        assertEquals(setOf(first, second), store.inboxSeen())
    }

    @Test fun failedDownloadIsRetried() {
        val bad = userFile(books, "bad.epub", "BAD")
        userFile(books, "good.epub", "GOOD")
        drive.failDownload[bad] = DriveException(500, "boom")

        val r = run()

        assertEquals(1, r.received)
        assertTrue(r.error != null)
        assertEquals(r.error, store.load().inboxError)
        assertFalse(local("bad.epub").exists())
        assertEquals("GOOD", local("good.epub").readText())
        assertTrue(bad !in store.inboxSeen())
        assertTrue(bad !in seenOnDrive())
        assertEquals(listOf("good.epub"), home.list()!!.sorted()) // no temp file left behind

        drive.failDownload.clear()
        val again = run()

        assertEquals(InboxResult(1, 0, null), again)
        assertEquals("BAD", local("bad.epub").readText())
        assertTrue(bad in store.inboxSeen())
    }

    @Test fun networkFailureStopsTheRunAndNothingIsMarkedSeen() {
        val a = userFile(books, "a.epub")
        drive.failDownload[a] = SocketTimeoutException("timeout")

        val r = run()

        assertEquals("нет сети", r.error)
        assertEquals(0, r.received)
        assertTrue(a !in store.inboxSeen())
        assertEquals(emptyList<String>(), home.list()!!.toList())
    }

    @Test fun lowSpaceSkipsAndReportsMaloMesta() {
        val big = userFile(books, "big.pdf", "0123456789")
        free = 200L * 1024 * 1024 + 5

        val r = run()

        assertEquals(InboxResult(received = 0, skippedExisting = 0, error = "мало места"), r)
        assertEquals("мало места", store.load().inboxError)
        assertFalse(local("big.pdf").exists())
        assertTrue(big !in store.inboxSeen())

        free = 200L * 1024 * 1024 + 10 // exactly 200 MB left afterwards: allowed
        assertEquals(InboxResult(1, 0, null), run())
        assertTrue(local("big.pdf").exists())
        assertNull(store.load().inboxError)
    }

    @Test fun googleNativeAndHiddenSkipped() {
        userFile(books, "Notes", mime = "application/vnd.google-apps.document")
        userFile(books, "Sheet", mime = "application/vnd.google-apps.spreadsheet")
        userFile(books, "a shortcut", mime = "application/vnd.google-apps.shortcut")
        userFile(books, ".DS_Store")
        val sub = drive.folder("sub", books)
        userFile(sub, ".hidden.epub")
        userFile(books, "book.epub", "BOOK")

        val r = run()

        assertEquals(InboxResult(1, 0, null), r)
        assertEquals(listOf("book.epub"), home.list()!!.toList())
    }

    @Test fun nestedCyrillicSeriesLandsAtSamePath() {
        val series = drive.folder("Гарри Поттер", books)
        val part = drive.folder("Часть 2 (новое)", series)
        userFile(series, "Том 1 — Философский камень.epub", "т1")
        userFile(part, "Книга с пробелами.fb2", "к2")

        val r = run()

        assertEquals(2, r.received)
        assertEquals("т1", local("Гарри Поттер/Том 1 — Философский камень.epub").readText())
        assertEquals("к2", local("Гарри Поттер/Часть 2 (новое)/Книга с пробелами.fb2").readText())
    }

    @Test fun unsafePathIsRejected() {
        val up = drive.folder("..", books)
        userFile(up, "escape.epub")

        val r = run()

        assertEquals(0, r.received)
        assertFalse(File(home.parentFile, "escape.epub").exists())
        assertEquals(emptyList<String>(), home.list()!!.toList())
    }

    @Test fun mtimeSetFromDrive() {
        userFile(books, "a.epub", mtime = 1_650_000_000_000L)

        run()

        assertEquals(1_650_000_000_000L, local("a.epub").lastModified())
    }

    @Test fun authLossReportsReEnableError() {
        userFile(books, "a.epub")
        drive.failListTree = DriveAuthException("401")

        val r = run()

        assertEquals(InboxResult(0, 0, "нет доступа к Диску — включите заново"), r)
        assertEquals("нет доступа к Диску — включите заново", store.load().inboxError)

        drive.failListTree = DriveException(403, "insufficient scope")
        assertEquals("нет доступа к Диску — включите заново", run().error)
        assertFalse(local("a.epub").exists())
    }

    @Test fun noBooksFolderCreatesNothing() {
        drive.nodes.remove(books)
        val before = drive.nodes.keys.toList()

        val r = run()

        assertEquals(InboxResult(0, 0, null), r)
        assertEquals(before, drive.nodes.keys.toList())

        drive.nodes.clear()
        assertEquals(InboxResult(0, 0, null), run())
        assertTrue(drive.nodes.isEmpty())
    }

    @Test fun statusIsMergedIntoTheStore() {
        userFile(books, "a.epub")
        userFile(books, "b.epub")
        store.update { it.copy(lastError = "нет сети", inboxError = "мало места") }

        run()

        val s = store.load()
        assertEquals(now, s.inboxLastCheckAt)
        assertEquals(2, s.inboxReceived)
        assertNull(s.inboxError)
        assertTrue(s.inboxEnabled)
        assertTrue(s.signedIn)
        assertEquals("owner@example.com", s.account)
        assertEquals("нет сети", s.lastError) // the backup's own status is untouched
    }

    // ---- fix round 1 ----------------------------------------------------------------------------

    private fun putSeenFile(vararg ids: String): String {
        val f = File(tmp.root, "seen-${System.nanoTime()}.json")
        f.writeText(JSONObject().put("ids", JSONArray(ids.toList())).put("updated", 1).toString())
        return drive.upload(root, "inbox-seen.json", f, mapOf("kind" to "inbox-seen"), null).id
    }

    /** Puts [first] ahead of every existing node, so Drive "answers" with it first. */
    private fun putFirst(first: () -> Unit) {
        val keep = LinkedHashMap(drive.nodes)
        drive.nodes.clear()
        first()
        keep.forEach { (k, v) -> drive.nodes.putIfAbsent(k, v) }
    }

    @Test fun seenFileReadFailureIsFatalAndKeepsTheRecord() {
        val a = userFile(books, "a.epub")
        val seenId = putSeenFile(a)
        store.saveInboxSeen(emptySet()) // a wiped reader
        drive.failDownload[seenId] = DriveException(500, "backend error")
        drive.uploads.clear()

        val r = run()

        assertEquals(InboxResult(0, 0, "ошибка Google Диска (код 500)"), r)
        assertFalse(local("a.epub").exists())
        assertTrue(a !in drive.downloads)
        assertTrue(seenId in drive.nodes) // the only record of what was seen is kept
        assertTrue(drive.uploads.isEmpty())
        assertTrue(drive.deletes.isEmpty())
    }

    @Test fun cutOffSeenFileIsFatalNotCorrupt() {
        val a = userFile(books, "a.epub")
        val seenId = putSeenFile(a)
        store.saveInboxSeen(emptySet()) // a wiped reader
        drive.serveBytes[seenId] = """{"ids":["""".toByteArray() // shorter than the file: not its content
        drive.uploads.clear()

        val r = run()

        assertEquals(InboxResult(0, 0, "не удалось получить книгу с Диска"), r)
        assertFalse(local("a.epub").exists())
        assertTrue(seenId in drive.nodes)
        assertTrue(drive.uploads.isEmpty())
        assertTrue(drive.deletes.isEmpty())
    }

    @Test fun seenFileThatVanishedIsSkippedNotDeleted() {
        val a = userFile(books, "a.epub")
        val b = userFile(books, "b.epub", "B")
        val gone = putSeenFile(b)
        val kept = putSeenFile(a)
        drive.failDownload[gone] = DriveException(404, "not found")

        val r = run()

        assertEquals(InboxResult(1, 0, null), r) // b: its only record vanished mid-run
        assertTrue(a !in drive.downloads)
        assertTrue(gone !in drive.deletes)
        assertTrue(kept in drive.deletes)
    }

    @Test fun corruptSeenFileIsReplaced() {
        val badJson = File(tmp.root, "bad.json").apply { writeText("{not json") }
        val bad = drive.upload(root, "inbox-seen.json", badJson, mapOf("kind" to "inbox-seen"), null).id
        userFile(books, "a.epub")

        val r = run()

        assertEquals(InboxResult(1, 0, null), r)
        assertTrue(bad in drive.deletes)
        assertEquals(1, drive.nodes.values.count { it.name == "inbox-seen.json" })
    }

    @Test fun seenFilesNotMadeByTheAppAreIgnored() {
        val a = userFile(books, "a.epub")
        val foreign = drive.put(root, "inbox-seen.json", """{"ids":["$a"]}""".toByteArray())

        run()

        assertTrue(a in drive.downloads) // its content is not trusted
        assertTrue(foreign in drive.nodes)
        assertTrue(foreign !in drive.deletes)
    }

    @Test fun sameNamedFileBesideTheRootIsIgnored() {
        putFirst {
            drive.nodes["u-file"] = FakeDrive.Node("u-file", null, "Nekoshelf", false, "x".toByteArray(), emptyMap(), "text/plain")
        }
        val booksFile = drive.put(root, "books", "x".toByteArray(), mimeType = "text/plain")
        val booksNode = drive.nodes[booksFile]!!
        putFirst { drive.nodes[booksFile] = booksNode }
        userFile(books, "a.epub", "A")

        val r = run()

        assertEquals(InboxResult(1, 0, null), r)
        assertEquals("A", local("a.epub").readText())
    }

    @Test fun secondNekoshelfFolderPrefersTheBackupOne() {
        lateinit var myBooks: String
        putFirst {
            val mine = drive.putFolder(null, "Nekoshelf") // the owner's own, first in Drive's answer
            myBooks = drive.putFolder(mine, "books")
        }
        userFile(myBooks, "wrong.epub")
        drive.putFolder(drive.nodes[myBooks]!!.parent, "koreader") // a same-named folder alone is no proof
        drive.put(drive.folder("koreader", root), "koreader-2026-10-23.zip", "z".toByteArray(), mapOf("kind" to "koreader"))
        userFile(books, "right.epub", "R")

        val r = run()

        assertEquals(InboxResult(1, 0, null), r)
        assertEquals("R", local("right.epub").readText())
        assertFalse(local("wrong.epub").exists())
        assertEquals(root, drive.nodes.values.single { it.name == "inbox-seen.json" }.parent)
    }

    @Test fun ambiguousNekoshelfFoldersReportAnError() {
        val other = drive.putFolder(null, "Nekoshelf")
        drive.putFolder(other, "books")
        userFile(books, "a.epub")

        val r = run()

        assertEquals(InboxResult(0, 0, "на Диске несколько папок Nekoshelf"), r)
        assertEquals("на Диске несколько папок Nekoshelf", store.load().inboxError)
        assertFalse(local("a.epub").exists())
        assertTrue(drive.uploads.isEmpty())
    }

    @Test fun missingRootAfterABackupReportsAnError() {
        drive.nodes.clear()
        store.update { it.copy(lastSuccessAt = now - 1000) }

        val r = run()

        assertEquals(InboxResult(0, 0, "на Диске нет папки Nekoshelf"), r)
        assertTrue(drive.nodes.isEmpty())
    }

    @Test fun twoBooksFoldersReportAnError() {
        val second = drive.putFolder(root, "books")
        userFile(second, "a.epub")

        assertEquals("в папке Nekoshelf несколько папок books", run().error)
        assertFalse(local("a.epub").exists())
    }

    @Test fun filesInHiddenFoldersAreSkipped() {
        val hidden = drive.folder(".sync", books)
        userFile(hidden, "a.epub")
        val inner = drive.folder("Серия", hidden)
        userFile(inner, "b.epub")
        assertEquals(emptyList<TreeFile>(), inboxCandidates(listOf(tf("1", "ok/.cache/x.pdf")), emptySet()))

        val r = run()

        assertEquals(InboxResult(0, 0, null), r)
        assertEquals(emptyList<String>(), home.list()!!.toList())
    }

    @Test fun staleTempFilesAreSwept() {
        val old = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
        val sub = drive.folder("sub", books)
        userFile(sub, "a.epub")
        userFile(books, "b.epub")
        local("sub").mkdirs()
        val stale = local("sub/.inbox-123.part").apply { writeText("x"); setLastModified(old) }
        val staleTwice = local("sub/.inbox-456.part.part").apply { writeText("x"); setLastModified(old) }
        val staleTop = local(".inbox-1.part").apply { writeText("x"); setLastModified(old) }
        val fresh = local("sub/.inbox-789.part").apply { writeText("x") }
        val notOurs = local("sub/.keep.part").apply { writeText("x"); setLastModified(old) }

        run()

        assertFalse(stale.exists())
        assertFalse(staleTwice.exists())
        assertFalse(staleTop.exists())
        assertTrue(fresh.exists())
        assertTrue(notOurs.exists())
        assertTrue(local("sub/a.epub").exists())
    }

    @Test fun localSeenSetPersists() {
        store.saveInboxSeen(setOf("a", "b"))
        assertEquals(setOf("a", "b"), BackupStore(ApplicationProvider.getApplicationContext()).inboxSeen())
    }
}
