package app.meebook.launcher.backup

import androidx.test.core.app.ApplicationProvider
import app.meebook.launcher.backup.drive.Drive
import app.meebook.launcher.backup.drive.DriveAuthException
import app.meebook.launcher.backup.drive.DriveException
import app.meebook.launcher.backup.drive.RemoteFile
import app.meebook.launcher.backup.drive.ResumeStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId

/** In-memory Drive: folders and files by id, every call recorded. */
class FakeDrive : Drive {
    data class Node(val id: String, val parent: String?, val name: String, val folder: Boolean, var bytes: ByteArray, val props: Map<String, String>)

    val nodes = LinkedHashMap<String, Node>()
    val uploads = ArrayList<String>()
    val deletes = ArrayList<String>()
    var calls = 0
    private var nextId = 1

    /** Throws [bookUploadError] on the N-th book upload (1-based, counted across runs) when set. */
    var failBookUpload: Int? = null
    var bookUploadError: Exception = SocketTimeoutException("timeout")
    private var bookUploads = 0

    /** Thrown from every call while set. */
    var failEverything: Exception? = null

    /** The ResumeStore each upload got (for checking it is passed through). */
    val resumeStores = ArrayList<ResumeStore?>()

    private fun call() {
        calls++
        failEverything?.let { throw it }
    }

    override fun folder(name: String, parent: String?): String {
        call()
        nodes.values.firstOrNull { it.folder && it.parent == parent && it.name == name }?.let { return it.id }
        val id = "f${nextId++}"
        nodes[id] = Node(id, parent, name, true, ByteArray(0), emptyMap())
        return id
    }

    override fun list(parent: String): List<RemoteFile> {
        call()
        return nodes.values.filter { it.parent == parent }.map { it.remote() }
    }

    override fun listByProp(key: String, value: String): List<RemoteFile> {
        call()
        return nodes.values.filter { !it.folder && it.props[key] == value }.map { it.remote() }
    }

    override fun upload(parent: String, name: String, file: File, props: Map<String, String>, resume: ResumeStore?): RemoteFile {
        call()
        if (props["kind"] == "book") {
            bookUploads++
            if (bookUploads == failBookUpload) throw bookUploadError
        }
        require(nodes[parent]?.folder == true) { "no parent folder $parent" }
        resumeStores += resume
        val id = "x${nextId++}"
        nodes[id] = Node(id, parent, name, false, file.readBytes(), props)
        uploads += name
        return nodes[id]!!.remote()
    }

    override fun download(id: String, to: File) {
        call()
        to.writeBytes(nodes[id]?.bytes ?: throw DriveException(404, "not found"))
    }

    override fun delete(id: String) {
        call()
        nodes.remove(id) ?: throw DriveException(404, "not found")
        deletes += id
    }

    private fun Node.remote() = RemoteFile(id, name, bytes.size.toLong(), props)

    /** Slash path of a node from the Drive root, e.g. `Meebook/books/sub/b.pdf`. */
    fun pathOf(n: Node): String {
        val parts = ArrayList<String>()
        var cur: Node? = n
        while (cur != null) {
            parts += cur.name
            cur = cur.parent?.let { nodes[it] }
        }
        return parts.reversed().joinToString("/")
    }

    fun files(): Map<String, Node> = nodes.values.filter { !it.folder }.associateBy { pathOf(it) }
    fun filesIn(dir: String): List<String> = files().keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }.sorted()
}

@RunWith(RobolectricTestRunner::class)
class BackupRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Moscow")
    private val day0 = LocalDate.of(2026, 10, 23)
    private var now = at(day0)
    private lateinit var drive: FakeDrive
    private lateinit var store: BackupStore
    private lateinit var ko: File
    private lateinit var home: File
    private lateinit var cache: File
    private var manga: () -> InputStream? = { ByteArrayInputStream("tachibk".toByteArray()) }

    private fun at(day: LocalDate) = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private val charging = Power(50, charging = true)
    private val battery = Power(90, charging = false)

    @Before fun setUp() {
        drive = FakeDrive()
        store = BackupStore(ApplicationProvider.getApplicationContext())
        store.save(BackupStatus.EMPTY)
        store.signedIn("owner@example.com", now)
        ko = tmp.newFolder("koreader")
        File(ko, "settings.reader.lua").writeText("return {}")
        home = tmp.newFolder("Books")
        book("a.epub", "aaaa")
        book("sub/b.pdf", "bbbbbb")
        book("sub/deep/c.fb2", "cc")
        cache = tmp.newFolder("cache")
    }

    private fun book(rel: String, text: String, mtime: Long = 1_700_000_000_000L): File =
        File(home, rel).also { it.parentFile!!.mkdirs(); it.writeText(text); it.setLastModified(mtime) }

    private fun runner() = BackupRunner(drive, store, ko, { home }, { manga() }, cache, { now }, zone)

    private fun run(power: Power = charging, manual: Boolean = false) = runner().run(power, manual)

    private fun workDirClean() = !File(cache, "backup-work").exists() || File(cache, "backup-work").list()!!.isEmpty()

    @Test fun firstRunUploadsSnapshotMangaAndBooks() {
        val s = run()

        assertEquals(listOf("koreader-2026-10-23.zip"), drive.filesIn("Meebook/koreader"))
        assertEquals(listOf("manga-2026-10-23.tachibk"), drive.filesIn("Meebook/manga"))
        assertEquals("tachibk", String(drive.files()["Meebook/manga/manga-2026-10-23.tachibk"]!!.bytes))
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/deep/c.fb2"), drive.filesIn("Meebook/books"))
        val b = drive.files()["Meebook/books/sub/b.pdf"]!!
        assertEquals(mapOf("kind" to "book", "path" to "sub/b.pdf", "size" to "6", "mtime" to "1700000000000"), b.props)
        assertEquals("bbbbbb", String(b.bytes))
        // The zip carries the books manifest.
        val zip = File(tmp.newFolder(), "k.zip").also { it.writeBytes(drive.files()["Meebook/koreader/koreader-2026-10-23.zip"]!!.bytes) }
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/deep/c.fb2"), readManifest(zip).map { it.path })

        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastFailureAt)
        assertNull(s.lastError)
        assertEquals("2026-10-23", s.lastAttemptDay)
        assertEquals(1, s.koreaderSnapshots)
        assertEquals(1, s.mangaSnapshots)
        assertEquals(3, s.booksOnDrive)
        assertEquals(3, s.booksLocal)
        assertEquals(0, s.booksPending)
        assertTrue(s.booksCaughtUpOnce)
        assertTrue(s.signedIn)
        assertEquals("owner@example.com", s.account)
        assertEquals(s, store.load())
        assertTrue(workDirClean())
        assertTrue(drive.resumeStores.all { it === store })
    }

    @Test fun secondRunUploadsOnlyNewOrChangedBooks() {
        run()
        val oldA = drive.files()["Meebook/books/a.epub"]!!.id
        book("a.epub", "aaaa-longer", mtime = 1_700_000_500_000L)
        book("sub/d.epub", "dd")
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = run()

        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk", "a.epub", "d.epub"), drive.uploads)
        // The old copy of the changed book is replaced, not kept beside it.
        assertTrue(oldA in drive.deletes)
        val a = drive.nodes.values.filter { it.props["path"] == "a.epub" }
        assertEquals(1, a.size)
        assertEquals("aaaa-longer", String(a[0].bytes))
        assertEquals("1700000500000", a[0].props["mtime"])
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/d.epub", "sub/deep/c.fb2"), drive.filesIn("Meebook/books"))
        assertEquals(4, s.booksOnDrive)
        assertEquals(4, s.booksLocal)
        assertEquals(0, s.booksPending)
    }

    @Test fun deletedLocalBookStaysOnDrive() {
        run()
        File(home, "sub/b.pdf").delete()
        drive.uploads.clear()
        drive.deletes.clear()
        now = at(day0.plusDays(1))

        val s = run()

        assertTrue(drive.files().containsKey("Meebook/books/sub/b.pdf"))
        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk"), drive.uploads)
        assertTrue(drive.deletes.isEmpty())
        assertEquals(3, s.booksOnDrive)
        assertEquals(2, s.booksLocal)
        assertEquals(0, s.booksPending)
        assertEquals(now, s.lastSuccessAt)
    }

    @Test fun interruptedRunResumesWithRemainingBooks() {
        drive.failBookUpload = 2

        val failed = run()

        assertEquals(now, failed.lastFailureAt)
        assertEquals("нет сети", failed.lastError)
        assertNull(failed.lastSuccessAt)
        assertEquals(1, failed.booksOnDrive)
        assertFalse(failed.booksCaughtUpOnce)
        assertEquals(failed, store.load())
        assertTrue(workDirClean())

        drive.uploads.clear()
        now += 60_000
        val s = run()

        assertEquals(listOf("koreader-2026-10-23.zip", "manga-2026-10-23.tachibk", "b.pdf", "c.fb2"), drive.uploads)
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/deep/c.fb2"), drive.filesIn("Meebook/books"))
        assertEquals(now, s.lastSuccessAt)
        assertEquals(0, s.booksPending)
        assertTrue(s.booksCaughtUpOnce)
    }

    @Test fun retentionKeepsFourteen() {
        lateinit var s: BackupStatus
        for (i in 0 until 16) {
            now = at(day0.plusDays(i.toLong()))
            s = run()
        }
        val expected = (2 until 16).map { day0.plusDays(it.toLong()) }
        assertEquals(expected.map { "koreader-$it.zip" }, drive.filesIn("Meebook/koreader"))
        assertEquals(expected.map { "manga-$it.tachibk" }, drive.filesIn("Meebook/manga"))
        assertEquals(14, s.koreaderSnapshots)
        assertEquals(14, s.mangaSnapshots)
        // Books were never touched by retention.
        assertEquals(3, drive.filesIn("Meebook/books").size)
    }

    @Test fun sameDayRerunReplacesThatDaysSnapshot() {
        run()
        val first = drive.files()["Meebook/koreader/koreader-2026-10-23.zip"]!!.id
        now += 3_600_000

        val s = run()

        assertEquals(listOf("koreader-2026-10-23.zip"), drive.filesIn("Meebook/koreader"))
        assertEquals(listOf("manga-2026-10-23.tachibk"), drive.filesIn("Meebook/manga"))
        assertFalse(drive.nodes.containsKey(first))
        assertEquals(1, s.koreaderSnapshots)
        assertEquals(1, s.mangaSnapshots)
    }

    @Test fun mangaUnavailableIsSkippedNotFailed() {
        manga = { null }

        val s = run()

        assertTrue(drive.filesIn("Meebook/manga").isEmpty())
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastFailureAt)
        assertEquals(0, s.mangaSnapshots)
        assertEquals(1, s.koreaderSnapshots)
        assertEquals(3, s.booksOnDrive)
    }

    @Test fun mangaStreamFailingAfterOpenIsRecorded() {
        manga = {
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 3) 'x'.code else throw IOException("pipe broken")
            }
        }

        val s = run()

        assertEquals("Манга не ответила", s.lastError)
        assertEquals(now, s.lastFailureAt)
        assertNull(s.lastSuccessAt)
        assertTrue(drive.filesIn("Meebook/manga").isEmpty())
        assertTrue(workDirClean())
        // A broken «Манга» does not hold the rest back.
        assertEquals(listOf("koreader-2026-10-23.zip"), drive.filesIn("Meebook/koreader"))
        assertEquals(3, drive.filesIn("Meebook/books").size)
        assertEquals(0, s.booksPending)
        assertTrue(s.booksCaughtUpOnce)
    }

    @Test fun emptyMangaStreamIsRecordedAsFailure() {
        manga = { ByteArrayInputStream(ByteArray(0)) }

        val s = run()

        assertEquals("Манга не ответила", s.lastError)
        assertTrue(drive.filesIn("Meebook/manga").isEmpty())
    }

    @Test fun authErrorIsRecordedWithReSignInText() {
        drive.failEverything = DriveAuthException("Google Drive rejected the access token (HTTP 401)")

        val s = run()

        assertEquals("Google отозвал доступ — войдите заново", s.lastError)
        assertEquals(now, s.lastFailureAt)
        assertNull(s.lastSuccessAt)
        assertEquals(s, store.load())
        assertTrue(workDirClean())
    }

    @Test fun driveErrorIsRecordedWithItsCode() {
        drive.failBookUpload = 1
        drive.bookUploadError = DriveException(403, "Google Drive request failed (HTTP 403)")

        assertEquals("ошибка Google Диска (код 403)", run().lastError)
    }

    @Test fun noNetworkIsRecorded() {
        drive.failEverything = UnknownHostException("www.googleapis.com")

        assertEquals("нет сети", run().lastError)
    }

    @Test fun unreadableKoreaderIsRecorded() {
        ko.deleteRecursively()

        val s = run()

        assertEquals("не удалось собрать бэкап KOReader", s.lastError)
        assertEquals(0, drive.uploads.size)
        assertTrue(workDirClean())
    }

    @Test fun booksWaitForChargerUntilCaughtUp() {
        val s = run(power = battery)

        assertEquals(listOf("koreader-2026-10-23.zip", "manga-2026-10-23.tachibk"), drive.uploads)
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastFailureAt)
        assertEquals(3, s.booksPending)
        assertEquals(3, s.booksLocal)
        assertEquals(0, s.booksOnDrive)
        assertFalse(s.booksCaughtUpOnce)

        // Pressed on purpose: books go up even on battery.
        now += 60_000
        val manual = run(power = battery, manual = true)
        assertEquals(0, manual.booksPending)
        assertTrue(manual.booksCaughtUpOnce)

        // Once caught up, a new book follows the normal battery rule.
        book("e.epub", "e")
        now = at(day0.plusDays(1))
        val later = run(power = battery)
        assertEquals(0, later.booksPending)
        assertTrue(drive.files().containsKey("Meebook/books/e.epub"))
    }

    @Test fun successfulRunClearsResumeSessions() {
        store.put("p|big.epub|10|1", "https://upload/session", 5)
        drive.failBookUpload = 1
        run()
        assertEquals("https://upload/session" to 5L, store.get("p|big.epub|10|1"))

        run()
        assertNull(store.get("p|big.epub|10|1"))
    }

    @Test fun remoteQueriesFindNewestSnapshotsAndBooks() {
        val r = runner()
        assertFalse(r.hasRemoteBackups())
        assertFalse(r.latestSnapshot(File(tmp.root, "none.zip")))
        assertFalse(r.latestMangaBackup(File(tmp.root, "none.tachibk")))

        run()
        manga = { ByteArrayInputStream("newer".toByteArray()) }
        now = at(day0.plusDays(1))
        run()

        assertTrue(r.hasRemoteBackups())
        val zip = File(tmp.root, "latest.zip")
        assertTrue(r.latestSnapshot(zip))
        assertArrayEquals(drive.files()["Meebook/koreader/koreader-2026-10-24.zip"]!!.bytes, zip.readBytes())
        val mb = File(tmp.root, "latest.tachibk")
        assertTrue(r.latestMangaBackup(mb))
        assertEquals("newer", mb.readText())

        val to = File(tmp.root, "b.pdf")
        r.downloadBook(BookEntry("sub/b.pdf", 6, 1_700_000_000_000L), to)
        assertEquals("bbbbbb", to.readText())
        assertThrows(IOException::class.java) { r.downloadBook(BookEntry("nope.epub", 1, 1), File(tmp.root, "nope")) }
    }

    @Test fun signInClearsFailureAndSignOutForgetsAccount() {
        drive.failEverything = UnknownHostException("x")
        run()
        assertEquals("нет сети", store.load().lastError)

        store.signedIn("other@example.com", now + 5)
        val s = store.load()
        assertTrue(s.signedIn)
        assertEquals("other@example.com", s.account)
        assertEquals(now + 5, s.signedInAt)
        assertNull(s.lastFailureAt)
        assertNull(s.lastError)
        assertEquals("2026-10-23", s.lastAttemptDay)

        store.put("k", "u", 1)
        store.signedOut()
        assertFalse(store.load().signedIn)
        assertNull(store.load().account)
        assertNull(store.get("k"))
    }

    @Test fun storeRoundTripsEveryField() {
        val s = BackupStatus(
            signedIn = true, account = "a@b", lastSuccessAt = 1, lastFailureAt = 2, lastError = "нет сети",
            lastAttemptDay = "2026-10-23", restoreOfferPending = true, koreaderSnapshots = 3, mangaSnapshots = 4,
            booksOnDrive = 5, booksLocal = 6, booksPending = 7, booksCaughtUpOnce = true, signedInAt = 8,
        )
        store.save(s)
        assertEquals(s, BackupStore(ApplicationProvider.getApplicationContext()).load())
        store.save(BackupStatus.EMPTY)
        assertEquals(BackupStatus.EMPTY, store.load())
    }

    @Test fun resumeSessionsRoundTripUrlsWithSeparators() {
        store.put("f1|a|b.epub|10|2", "https://x/upload?id=1|2&a=b", 262144)
        assertEquals("https://x/upload?id=1|2&a=b" to 262144L, store.get("f1|a|b.epub|10|2"))
        store.clear("f1|a|b.epub|10|2")
        assertNull(store.get("f1|a|b.epub|10|2"))
    }
}
