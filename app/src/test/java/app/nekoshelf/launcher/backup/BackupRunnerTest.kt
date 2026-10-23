package app.nekoshelf.launcher.backup

import androidx.test.core.app.ApplicationProvider
import app.nekoshelf.launcher.backup.drive.Drive
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.DriveException
import app.nekoshelf.launcher.backup.drive.RemoteFile
import app.nekoshelf.launcher.backup.drive.TreeFile
import app.nekoshelf.launcher.backup.drive.ResumeStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.pm.ProviderInfo
import app.nekoshelf.launcher.backup.drive.TokenUnavailableException
import app.nekoshelf.launcher.data.manga.FakeMangaProvider
import app.nekoshelf.launcher.data.manga.MangaBridge
import org.robolectric.Robolectric
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A minimal «Манга» backup: gzip of [text] (the runner checks the gzip magic). */
fun tachibk(text: String = "tachibk"): ByteArray =
    ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

fun gunzipText(bytes: ByteArray): String = GZIPInputStream(bytes.inputStream()).use { String(it.readBytes()) }

/** In-memory Drive: folders and files by id, every call recorded. */
class FakeDrive : Drive {
    data class Node(
        val id: String,
        val parent: String?,
        val name: String,
        val folder: Boolean,
        var bytes: ByteArray,
        val props: Map<String, String>,
        val mimeType: String = if (folder) FOLDER_MIME else "",
        val modifiedTime: Long = 0L,
    )

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

    /** Thrown by the upload of a file with this name while set. */
    var failUploadNamed: Pair<String, Exception>? = null

    /** Deletes answer 404 (and delete nothing) while set. */
    var deletesAnswer404 = false

    /** Thrown by every delete while set. */
    var failDelete: Exception? = null

    /** Downloads of these ids throw the mapped exception while set. */
    val failDownload = HashMap<String, Exception>()

    /** Thrown by [listTree] while set. */
    var failListTree: Exception? = null

    /** Ids passed to [download], in order. */
    val downloads = ArrayList<String>()

    /** (parent, name) of every [findChildren] call. */
    val childQueries = ArrayList<Pair<String, String>>()

    /** [listTree] calls (the readonly-only listing). */
    var treeCalls = 0

    /** Downloads of these ids write these bytes instead of the file's own (a cut-off transfer). */
    val serveBytes = HashMap<String, ByteArray>()

    /** Called at the start of every upload with the file's name (e.g. to hold a thread there). */
    var onUpload: ((String) -> Unit)? = null

    /** A file someone (the owner via Drive's UI, or the app) put under [parent]; returns its id. */
    fun put(
        parent: String,
        name: String,
        bytes: ByteArray,
        props: Map<String, String> = emptyMap(),
        mimeType: String = "application/octet-stream",
        modifiedTime: Long = 0L,
    ): String {
        val id = "u${nextId++}"
        nodes[id] = Node(id, parent, name, false, bytes, props, mimeType, modifiedTime)
        return id
    }

    /** A folder someone made under [parent] (null = My Drive), even beside a same-named one; returns its id. */
    fun putFolder(parent: String?, name: String): String {
        val id = "d${nextId++}"
        nodes[id] = Node(id, parent, name, true, ByteArray(0), emptyMap())
        return id
    }

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

    override fun listTree(rootId: String): List<TreeFile> {
        call()
        treeCalls++
        failListTree?.let { throw it }
        val out = ArrayList<TreeFile>()
        fun walk(id: String, prefix: String) {
            for (n in nodes.values.filter { it.parent == id }) {
                if (n.folder) walk(n.id, prefix + n.name + "/") else out += TreeFile(n.remote(), prefix + n.name)
            }
        }
        walk(rootId, "")
        return out
    }

    override fun findChild(parent: String, name: String): RemoteFile? = findChildren(parent, name).firstOrNull()

    override fun findChildren(parent: String, name: String): List<RemoteFile> {
        call()
        childQueries += parent to name
        val p = if (parent == "root") null else parent // Drive's alias for "My Drive"
        return nodes.values.filter { it.parent == p && it.name == name }.map { it.remote() }
    }

    override fun upload(parent: String, name: String, file: File, props: Map<String, String>, resume: ResumeStore?): RemoteFile {
        call()
        onUpload?.invoke(name)
        if (props["kind"] == "book") {
            bookUploads++
            if (bookUploads == failBookUpload) throw bookUploadError
        }
        failUploadNamed?.let { (n, e) -> if (n == name) throw e }
        require(nodes[parent]?.folder == true) { "no parent folder $parent" }
        // Drive's appProperties limits: 124 UTF-8 bytes per key + value, 30 per app.
        if (props.size > 30) throw DriveException(400, "too many properties")
        for ((k, v) in props) {
            if ((k + v).toByteArray(Charsets.UTF_8).size > 124) throw DriveException(400, "property too long")
        }
        resumeStores += resume
        val id = "x${nextId++}"
        nodes[id] = Node(id, parent, name, false, file.readBytes(), props)
        uploads += name
        return nodes[id]!!.remote()
    }

    override fun download(id: String, to: File) {
        call()
        downloads += id
        failDownload[id]?.let { throw it }
        to.writeBytes(serveBytes[id] ?: nodes[id]?.bytes ?: throw DriveException(404, "not found"))
    }

    override fun delete(id: String) {
        call()
        if (deletesAnswer404) throw DriveException(404, "not found")
        failDelete?.let { throw it }
        nodes.remove(id) ?: throw DriveException(404, "not found")
        deletes += id
    }

    private fun Node.remote() = RemoteFile(id, name, bytes.size.toLong(), props, mimeType, modifiedTime, listOfNotNull(parent))

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }

    /** Slash path of a node from the Drive root, e.g. `Nekoshelf/books/sub/b.pdf`. */
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
    private var manga: () -> InputStream? = { ByteArrayInputStream(tachibk()) }
    private var mangaAvailable: () -> Boolean = { true }

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

    private fun runner(
        inbox: InboxRunner? = null,
        unmetered: Boolean = true,
        readonly: Boolean = inbox != null,
        readonlyLost: () -> Exception? = { null },
    ) = BackupRunner(drive, store, ko, { home }, { manga() }, { mangaAvailable() }, cache, { now }, zone, inbox, { unmetered }, readonly, readonlyLost)

    private fun run(power: Power = charging, manual: Boolean = false, powerNow: () -> Power = { power }) =
        runner().run(power, manual, powerNow)

    private fun workDirClean() = !File(cache, "backup-work").exists() || File(cache, "backup-work").list()!!.isEmpty()

    @Test fun firstRunUploadsSnapshotMangaAndBooks() {
        val s = run()

        assertEquals(listOf("koreader-2026-10-23.zip"), drive.filesIn("Nekoshelf/koreader"))
        assertEquals(listOf("manga-2026-10-23.tachibk"), drive.filesIn("Nekoshelf/manga"))
        assertEquals("tachibk", gunzipText(drive.files()["Nekoshelf/manga/manga-2026-10-23.tachibk"]!!.bytes))
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/deep/c.fb2"), drive.filesIn("Nekoshelf/books"))
        val b = drive.files()["Nekoshelf/books/sub/b.pdf"]!!
        assertEquals(
            mapOf("kind" to "book", "pid" to pathId("sub/b.pdf"), "size" to "6", "mtime" to "1700000000000", "p0" to "sub/b.pdf"),
            b.props,
        )
        assertEquals("bbbbbb", String(b.bytes))
        // The zip carries the books manifest.
        val zip = File(tmp.newFolder(), "k.zip").also { it.writeBytes(drive.files()["Nekoshelf/koreader/koreader-2026-10-23.zip"]!!.bytes) }
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/deep/c.fb2"), readManifest(zip).map { it.path })

        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastFailureAt)
        assertNull(s.lastError)
        assertNull(s.failedAutoDay)
        assertEquals(0, s.failedAutoAttempts)
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
        val oldA = drive.files()["Nekoshelf/books/a.epub"]!!.id
        book("a.epub", "aaaa-longer", mtime = 1_700_000_500_000L)
        book("sub/d.epub", "dd")
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = run()

        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk", "a.epub", "d.epub"), drive.uploads)
        // The old copy of the changed book is replaced, not kept beside it.
        assertTrue(oldA in drive.deletes)
        val a = drive.nodes.values.filter { bookPathOf(it.props) == "a.epub" }
        assertEquals(1, a.size)
        assertEquals("aaaa-longer", String(a[0].bytes))
        assertEquals("1700000500000", a[0].props["mtime"])
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/d.epub", "sub/deep/c.fb2"), drive.filesIn("Nekoshelf/books"))
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

        assertTrue(drive.files().containsKey("Nekoshelf/books/sub/b.pdf"))
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
        assertEquals(2, failed.booksPending)
        assertFalse(failed.booksCaughtUpOnce)
        assertEquals(failed, store.load())
        assertTrue(workDirClean())

        drive.uploads.clear()
        now += 60_000
        val s = run()

        assertEquals(listOf("koreader-2026-10-23.zip", "manga-2026-10-23.tachibk", "b.pdf", "c.fb2"), drive.uploads)
        assertEquals(listOf("a.epub", "sub/b.pdf", "sub/deep/c.fb2"), drive.filesIn("Nekoshelf/books"))
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
        assertEquals(expected.map { "koreader-$it.zip" }, drive.filesIn("Nekoshelf/koreader"))
        assertEquals(expected.map { "manga-$it.tachibk" }, drive.filesIn("Nekoshelf/manga"))
        assertEquals(14, s.koreaderSnapshots)
        assertEquals(14, s.mangaSnapshots)
        // Books were never touched by retention.
        assertEquals(3, drive.filesIn("Nekoshelf/books").size)
    }

    @Test fun sameDayRerunReplacesThatDaysSnapshot() {
        run()
        val first = drive.files()["Nekoshelf/koreader/koreader-2026-10-23.zip"]!!.id
        now += 3_600_000

        val s = run()

        assertEquals(listOf("koreader-2026-10-23.zip"), drive.filesIn("Nekoshelf/koreader"))
        assertEquals(listOf("manga-2026-10-23.tachibk"), drive.filesIn("Nekoshelf/manga"))
        assertFalse(drive.nodes.containsKey(first))
        assertEquals(1, s.koreaderSnapshots)
        assertEquals(1, s.mangaSnapshots)
    }

    @Test fun mangaUnavailableIsSkippedNotFailed() {
        // Not installed, or the bridge permission missing: the only "skipped" case (contract).
        mangaAvailable = { false }
        manga = { throw AssertionError("not asked when «Манга» is unavailable") }

        val s = run()

        assertTrue(drive.filesIn("Nekoshelf/manga").isEmpty())
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastFailureAt)
        assertEquals(0, s.mangaSnapshots)
        assertEquals(1, s.koreaderSnapshots)
        assertEquals(3, s.booksOnDrive)
    }

    @Test fun mangaGivingNoStreamIsAFailureNotASkip() {
        manga = { null }

        val s = run()

        assertEquals("Манга не ответила", s.lastError)
        assertEquals(now, s.lastSuccessAt) // R18: KOReader and books still count for the day
        assertTrue(s.lastFailureAt!! > s.lastSuccessAt!!)
        assertTrue(drive.filesIn("Nekoshelf/manga").isEmpty())
    }

    @Test fun failingBridgeBackupIsRecordedAsFailure() {
        // The real client against a provider whose `backup` throws FileNotFoundException.
        FakeMangaProvider.reset()
        val info = ProviderInfo().apply {
            authority = MangaBridge.AUTHORITY
            packageName = MangaBridge.PACKAGE
            name = FakeMangaProvider::class.java.name
            exported = true
        }
        Robolectric.buildContentProvider(FakeMangaProvider::class.java).create(info)
        val bridge = MangaBridge(ApplicationProvider.getApplicationContext(), retryDelayMs = 0)
        manga = { bridge.backup() }

        val s = run()

        assertEquals("Манга не ответила", s.lastError)
        assertTrue(drive.filesIn("Nekoshelf/manga").isEmpty())
        FakeMangaProvider.reset()
    }

    @Test fun mangaBackupWithoutGzipMagicIsRecordedAsFailure() {
        manga = { ByteArrayInputStream("<html>error</html>".toByteArray()) }

        val s = run()

        assertEquals("Манга не ответила", s.lastError)
        assertTrue(drive.filesIn("Nekoshelf/manga").isEmpty())
        assertTrue(workDirClean())
    }

    @Test fun mangaStreamFailingAfterOpenIsRecorded() {
        manga = {
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 3) 'x'.code else throw IOException("pipe broken")
            }
        }

        val s = run()

        // R18: the rest worked, so the day counts as backed up; the «Манга» error is a later failure.
        assertEquals("Манга не ответила", s.lastError)
        assertEquals(now, s.lastSuccessAt)
        assertTrue(s.lastFailureAt!! > s.lastSuccessAt!!)
        assertFalse(shouldRunAuto(s, day0, charging, zone))
        assertTrue(drive.filesIn("Nekoshelf/manga").isEmpty())
        assertTrue(workDirClean())
        // A broken «Манга» does not hold the rest back.
        assertEquals(listOf("koreader-2026-10-23.zip"), drive.filesIn("Nekoshelf/koreader"))
        assertEquals(3, drive.filesIn("Nekoshelf/books").size)
        assertEquals(0, s.booksPending)
        assertTrue(s.booksCaughtUpOnce)
    }

    @Test fun emptyMangaStreamIsRecordedAsFailure() {
        manga = { ByteArrayInputStream(ByteArray(0)) }

        val s = run()

        assertEquals("Манга не ответила", s.lastError)
        assertTrue(drive.filesIn("Nekoshelf/manga").isEmpty())
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

    @Test fun tokenUnavailableIsNoNetworkNotRevokedAccess() {
        drive.failEverything = TokenUnavailableException("Google Play services gave no token", null)

        val s = run()

        assertEquals("нет сети", s.lastError)
        assertEquals(0, s.failedAutoAttempts)
    }

    @Test fun pendingRestoreOfferHoldsAutoAndManualRuns() {
        store.update { it.copy(restoreOfferPending = true) }
        val before = store.load()

        assertEquals(before, run())
        assertEquals(before, run(manual = true))

        assertEquals(0, drive.calls)
        assertEquals(before, store.load())
    }

    @Test fun failedAutoRunsAreCountedPerDayExceptNetworkFailures() {
        drive.failBookUpload = 1
        drive.bookUploadError = DriveException(403, "Google Drive request failed (HTTP 403)")
        val first = run()
        assertEquals("2026-10-23", first.failedAutoDay)
        assertEquals(1, first.failedAutoAttempts)

        // Manual runs are unlimited and not counted.
        drive.failBookUpload = 4 // the 4th book upload: this run's retry of a.epub
        now += 60_000
        assertEquals(1, run(manual = true).failedAutoAttempts)

        // Network-class failures may retry on every wake: not counted.
        drive.failEverything = UnknownHostException("www.googleapis.com")
        now += 60_000
        assertEquals(1, run().failedAutoAttempts)
        drive.failEverything = null

        drive.failUploadNamed = "koreader-2026-10-23.zip" to DriveException(500, "x")
        now += 60_000
        run()
        now += 60_000
        val third = run()
        assertEquals(3, third.failedAutoAttempts)
        assertFalse(shouldRunAuto(third, day0, charging, zone))
        assertTrue(shouldRunAuto(third, day0.plusDays(1), charging, zone))
    }

    @Test fun duplicateCopyDeleteErrorDoesNotStopTheOtherBooks() {
        run()
        val current = drive.files()["Nekoshelf/books/a.epub"]!!
        val junk = File(tmp.root, "junk").apply { writeText("old version") }
        drive.upload(current.parent!!, "a.epub", junk, bookAppProperties(BookEntry("a.epub", 11, 5)), null)
        book("z.epub", "zz")
        drive.failDelete = DriveException(500, "Google Drive request failed (HTTP 500)")
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = run()

        assertTrue("z.epub" in drive.uploads)
        assertEquals(0, s.booksPending)
        assertEquals("ошибка Google Диска (код 500)", s.lastError)
    }

    @Test fun unpluggedDuringFirstSyncStopsTheBooksWithoutFailing() {
        // Charging at the start; unplugged once the first book is up.
        val s = run(power = charging) { if (drive.filesIn("Nekoshelf/books").isNotEmpty()) battery else charging }

        assertEquals(1, drive.filesIn("Nekoshelf/books").size)
        assertEquals(2, s.booksPending)
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastError)
        assertFalse(s.booksCaughtUpOnce)
        // R31: back on the charger later today, the rest may go up.
        assertTrue(shouldRunAuto(s, day0, charging, zone))
    }

    @Test fun manualRunIgnoresUnpluggingBetweenBooks() {
        val s = run(power = battery, manual = true) { battery }
        assertEquals(0, s.booksPending)
        assertEquals(3, drive.filesIn("Nekoshelf/books").size)
    }

    @Test fun errorTextFallbackOnlyReplacesTheGenericCase() {
        val check = "не удалось проверить Google Диск"
        assertEquals(check, backupErrorText(IllegalStateException("x"), check))
        assertEquals("нет сети", backupErrorText(UnknownHostException("x"), check))
        assertEquals("нет сети", backupErrorText(TokenUnavailableException("x", null), check))
        assertEquals("Google отозвал доступ — войдите заново", backupErrorText(DriveAuthException("x"), check))
        assertEquals("не удалось сделать бэкап", backupErrorText(IllegalStateException("x")))
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
        assertTrue(drive.files().containsKey("Nekoshelf/books/e.epub"))
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
        manga = { ByteArrayInputStream(tachibk("newer")) }
        now = at(day0.plusDays(1))
        run()

        assertTrue(r.hasRemoteBackups())
        val zip = File(tmp.root, "latest.zip")
        assertTrue(r.latestSnapshot(zip))
        assertArrayEquals(drive.files()["Nekoshelf/koreader/koreader-2026-10-24.zip"]!!.bytes, zip.readBytes())
        val mb = File(tmp.root, "latest.tachibk")
        assertTrue(r.latestMangaBackup(mb))
        assertEquals("newer", gunzipText(mb.readBytes()))

        val to = File(tmp.root, "b.pdf")
        r.downloadBook(BookEntry("sub/b.pdf", 6, 1_700_000_000_000L), to)
        assertEquals("bbbbbb", to.readText())
        assertThrows(IOException::class.java) { r.downloadBook(BookEntry("nope.epub", 1, 1), File(tmp.root, "nope")) }
    }

    @Test fun latestSnapshotDateIsTheNewestKoreaderOrElseManga() {
        val r = runner()
        assertNull(r.latestSnapshotDate())
        assertNull(r.latestMangaBackupName())

        run()
        now = at(day0.plusDays(2))
        run()
        assertEquals(day0.plusDays(2), r.latestSnapshotDate())
        assertEquals("manga-2026-10-25.tachibk", r.latestMangaBackupName())

        // The file looked up is the file downloaded, even if a newer one appears in between.
        val named = r.latestMangaBackupFile()!!
        manga = { ByteArrayInputStream(tachibk("even newer")) }
        now = at(day0.plusDays(3))
        run()
        val got = File(tmp.root, "named.tachibk")
        r.download(named, got)
        assertEquals("tachibk", gunzipText(got.readBytes()))
        assertEquals("manga-2026-10-26.tachibk", r.latestMangaBackupName())
        now = at(day0.plusDays(2))
        drive.nodes.values.filter { it.name.endsWith("-2026-10-26.zip") || it.name.endsWith("-2026-10-26.tachibk") }
            .map { it.id }.forEach { drive.nodes.remove(it) }

        // No KOReader snapshot left (or none matching the name): the «Манга» date counts.
        drive.nodes.values.filter { it.name.startsWith("koreader-") }.map { it.id }.forEach { drive.nodes.remove(it) }
        drive.nodes.values.filter { it.name == "manga-2026-10-25.tachibk" }.map { it.id }.forEach { drive.nodes.remove(it) }
        assertEquals(day0, r.latestSnapshotDate())
    }

    @Test fun longCyrillicPathRoundTripsThroughDrive() {
        val rel = "Фантастика/Аркадий и Борис Стругацкие/Собрание сочинений в одиннадцати томах 📚/" +
            "Том седьмой. Понедельник начинается в субботу. Сказка о Тройке. Улитка на склоне (1966).fb2"
        assertTrue(rel.toByteArray(Charsets.UTF_8).size >= 300)
        val f = book(rel, "текст")

        val s = run()

        val node = drive.files()["Nekoshelf/books/$rel"]!!
        assertEquals(rel, bookPathOf(node.props))
        assertEquals(pathId(rel), node.props["pid"])
        assertTrue(node.props.keys.count { Regex("""p\d+""").matches(it) } >= 3)
        assertEquals(0, s.booksPending)
        assertEquals(4, s.booksOnDrive)

        // Indexed back to the identical path: nothing to upload the next day.
        drive.uploads.clear()
        now = at(day0.plusDays(1))
        assertEquals(0, run().booksPending)
        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk"), drive.uploads)

        val to = File(tmp.root, "dl.fb2")
        runner().downloadBook(BookEntry(rel, f.length(), f.lastModified()), to)
        assertEquals("текст", to.readText())
    }

    @Test fun pathChunksFitDriveLimitAtCharacterBoundaries() {
        val rel = "я".repeat(70) + "😀".repeat(40) + "a".repeat(130)
        val props = bookAppProperties(BookEntry(rel, 1, 2))
        for ((k, v) in props) assertTrue(k, (k + v).toByteArray(Charsets.UTF_8).size <= DRIVE_PROP_MAX_BYTES)
        assertTrue(props.values.none { v -> v.isNotEmpty() && (v.first().isLowSurrogate() || v.last().isHighSurrogate()) })
        assertEquals(rel, bookPathOf(props))
        assertThrows(IOException::class.java) { bookAppProperties(BookEntry("я".repeat(2000), 1, 2)) }
    }

    @Test fun oneRejectedBookDoesNotBlockTheRest() {
        drive.failBookUpload = 1
        drive.bookUploadError = DriveException(400, "Google Drive request failed (HTTP 400)")

        val s = run()

        assertEquals(listOf("sub/b.pdf", "sub/deep/c.fb2"), drive.filesIn("Nekoshelf/books"))
        assertEquals(1, s.booksPending)
        assertEquals(2, s.booksOnDrive)
        assertEquals("ошибка Google Диска (код 400)", s.lastError)
        assertEquals(now, s.lastFailureAt)
        assertNull(s.lastSuccessAt)
        assertFalse(s.booksCaughtUpOnce)
    }

    @Test fun overlappingRunReturnsAtOnceWithoutTouchingDrive() {
        var nested: BackupStatus? = null
        var stored: BackupStatus? = null
        var callsBefore = -1
        var callsAfter = -2
        var outer = true
        manga = {
            if (outer) {
                outer = false
                callsBefore = drive.calls
                stored = store.load()
                val t = Thread { nested = runner().run(charging, manual = true) }
                t.start()
                t.join(10_000)
                callsAfter = drive.calls
            }
            ByteArrayInputStream(tachibk())
        }

        val s = run()

        assertEquals(stored, nested)
        assertEquals(callsBefore, callsAfter)
        assertEquals(now, s.lastSuccessAt)
        // The lock is released afterwards.
        manga = { ByteArrayInputStream(tachibk()) }
        now += 1
        assertEquals(now, run().lastSuccessAt)
    }

    @Test fun staleWorkFoldersAreRemovedButNotOtherRuns() {
        val base = File(cache, "backup-work").apply { mkdirs() }
        val old = File(base, "run-old").apply { mkdirs() }
        File(old, "koreader-2026-10-20.zip").writeText("x")
        old.setLastModified(System.currentTimeMillis() - 2 * 24 * 3_600_000L)
        val other = File(base, "run-other").apply { mkdirs() }

        run()

        assertFalse(old.exists())
        assertTrue(other.exists())
        assertEquals(listOf("run-other"), base.list()!!.toList())
    }

    @Test fun deletesThatAnswer404CountAsDone() {
        run()
        drive.deletesAnswer404 = true
        now += 3_600_000

        val s = run()

        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastError)
    }

    @Test fun staleCopiesOfAPresentBookAreRemoved() {
        run()
        val current = drive.files()["Nekoshelf/books/a.epub"]!!
        val junk = File(tmp.root, "junk").apply { writeText("old version") }
        val stale = drive.upload(current.parent!!, "a.epub", junk, bookAppProperties(BookEntry("a.epub", 11, 5)), null)
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        run()

        assertFalse(drive.nodes.containsKey(stale.id))
        assertTrue(drive.nodes.containsKey(current.id))
        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk"), drive.uploads)
    }

    @Test fun retentionNeverDeletesTodaysUploadWhenClockWentBack() {
        for (i in 1..14) {
            now = at(day0.plusDays(i.toLong()))
            run()
        }
        now = at(day0)

        val s = run()

        assertTrue("koreader-2026-10-23.zip" in drive.filesIn("Nekoshelf/koreader"))
        assertTrue("manga-2026-10-23.tachibk" in drive.filesIn("Nekoshelf/manga"))
        assertEquals(15, s.koreaderSnapshots)
        assertEquals(now, s.lastSuccessAt)
    }

    @Test fun signOutDuringRunLeavesStatusAlone() {
        manga = {
            store.signedOut()
            ByteArrayInputStream(tachibk())
        }

        val s = run()

        assertEquals(BackupStatus.EMPTY, s)
        assertEquals(BackupStatus.EMPTY, store.load())
    }

    @Test fun accountChangeDuringRunLeavesStatusAlone() {
        manga = {
            store.signedIn("other@example.com", now + 1)
            ByteArrayInputStream(tachibk())
        }

        run()

        val s = store.load()
        assertEquals("other@example.com", s.account)
        assertNull(s.lastSuccessAt)
        assertEquals(0, s.booksOnDrive)
        assertEquals(0, s.failedAutoAttempts)
    }

    @Test fun sameFileNameInDifferentFoldersAreSeparateBooks() {
        val x = book("x/notes.txt", "one")
        val y = book("y/notes.txt", "two!")

        run()

        assertEquals("one", String(drive.files()["Nekoshelf/books/x/notes.txt"]!!.bytes))
        assertEquals("two!", String(drive.files()["Nekoshelf/books/y/notes.txt"]!!.bytes))
        drive.uploads.clear()
        now = at(day0.plusDays(1))
        assertEquals(0, run().booksPending)
        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk"), drive.uploads)
        val r = runner()
        val tx = File(tmp.root, "x.txt").also { r.downloadBook(BookEntry("x/notes.txt", x.length(), x.lastModified()), it) }
        val ty = File(tmp.root, "y.txt").also { r.downloadBook(BookEntry("y/notes.txt", y.length(), y.lastModified()), it) }
        assertEquals("one", tx.readText())
        assertEquals("two!", ty.readText())
    }

    @Test fun failureAfterKoreaderUploadKeepsPreviousSuccess() {
        val first = run().lastSuccessAt
        now = at(day0.plusDays(1))
        drive.failUploadNamed = "manga-2026-10-24.tachibk" to UnknownHostException("www.googleapis.com")

        val s = run()

        assertTrue("koreader-2026-10-24.zip" in drive.filesIn("Nekoshelf/koreader"))
        assertEquals(first, s.lastSuccessAt)
        assertEquals(now, s.lastFailureAt)
        assertEquals("нет сети", s.lastError)
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
        assertEquals(0, s.failedAutoAttempts) // a network failure is not counted (R32)

        store.put("k", "u", 1)
        store.signedOut()
        assertFalse(store.load().signedIn)
        assertNull(store.load().account)
        assertNull(store.get("k"))
    }

    @Test fun storeRoundTripsEveryField() {
        val s = BackupStatus(
            signedIn = true, account = "a@b", lastSuccessAt = 1, lastFailureAt = 2, lastError = "нет сети",
            failedAutoDay = "2026-10-23", failedAutoAttempts = 2, restoreOfferPending = true, koreaderSnapshots = 3, mangaSnapshots = 4,
            booksOnDrive = 5, booksLocal = 6, booksPending = 7, booksCaughtUpOnce = true, signedInAt = 8,
            inboxEnabled = true, inboxLastCheckAt = 9, inboxReceived = 10, inboxError = "мало места",
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

    // ---- the inbox and the readonly view -------------------------------------------------------

    private fun inbox(d: Drive = drive) = InboxRunner(d, store, { home }, { Long.MAX_VALUE }, { now })

    private fun booksFolder() = drive.folder("books", drive.folder("Nekoshelf", null))

    private fun userFile(parent: String, name: String, text: String) =
        drive.put(parent, name, text.toByteArray(), mimeType = "application/epub+zip")

    /** A run with the readonly grant (the checkbox on); [inbox] null when only the readonly view is wanted. */
    private fun grantedRun(inbox: InboxRunner? = inbox(), unmetered: Boolean = true, power: Power = charging) =
        runner(inbox, unmetered, readonly = true).run(power, false) { power }

    @Test fun inboxStepRunsFirstWhenEnabledAndUnmetered() {
        run()
        val series = drive.putFolder(booksFolder(), "Серия")
        userFile(series, "Том 1.epub", "том")
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = grantedRun()

        assertEquals("том", File(home, "Серия/Том 1.epub").readText())
        assertEquals(1, s.inboxReceived)
        assertNull(s.inboxError)
        assertEquals(now, s.inboxLastCheckAt)
        // The inbox's seen file went up before the snapshot, and the book was not uploaded back.
        assertEquals(listOf(INBOX_SEEN_FILE, "koreader-2026-10-24.zip", "manga-2026-10-24.tachibk"), drive.uploads)
        val zip = File(tmp.newFolder(), "k.zip").also { it.writeBytes(drive.files()["Nekoshelf/koreader/koreader-2026-10-24.zip"]!!.bytes) }
        assertTrue("Серия/Том 1.epub" in readManifest(zip).map { it.path })
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastError)
        assertEquals(4, s.booksLocal)
        assertEquals(4, s.booksOnDrive)
        assertEquals(0, s.booksPending)
    }

    @Test fun inboxWaitsForAnUnmeteredNetwork() {
        run()
        userFile(booksFolder(), "new.epub", "n")
        drive.downloads.clear()
        now = at(day0.plusDays(1))

        val s = grantedRun(unmetered = false)

        assertFalse(File(home, "new.epub").exists())
        assertTrue(drive.downloads.isEmpty())
        assertNull(s.inboxLastCheckAt)
        assertEquals(now, s.lastSuccessAt)
    }

    @Test fun restoreHoldPausesTheInboxStepToo() {
        run()
        userFile(booksFolder(), "new.epub", "n")
        store.update { it.copy(restoreOfferPending = true) }
        val calls = drive.calls

        grantedRun()

        assertEquals(calls, drive.calls)
        assertFalse(File(home, "new.epub").exists())
    }

    @Test fun inboxFailureDoesNotFailBackup() {
        run()
        val dropped = userFile(booksFolder(), "new.epub", "n")
        now = at(day0.plusDays(1))
        // Readonly access lost: every call of the inbox's Drive is rejected.
        val revoked = FakeDrive().apply { failEverything = DriveAuthException("Google Drive rejected the access token (HTTP 401)") }

        val s = grantedRun(inbox(revoked))

        assertEquals(INBOX_ERROR_NO_ACCESS, s.inboxError)
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastError)
        assertNull(s.lastFailureAt)
        assertEquals(0, s.failedAutoAttempts)
        assertTrue("koreader-2026-10-24.zip" in drive.filesIn("Nekoshelf/koreader"))
        assertFalse(File(home, "new.epub").exists())

        // A download cut off by the network stops only the inbox.
        drive.failDownload[dropped] = SocketTimeoutException("timeout")
        now += 60_000
        val t = grantedRun()
        assertEquals("нет сети", t.inboxError)
        assertEquals(now, t.lastSuccessAt)
        assertNull(t.lastError)

        // Even an inbox that throws out of its run.
        now += 60_000
        val broken = InboxRunner(drive, store, { home }, { Long.MAX_VALUE }, { throw IllegalStateException("clock") })
        val u = grantedRun(broken)
        assertEquals(now, u.lastSuccessAt)
        assertNull(u.lastError)
    }

    @Test fun localBookPresentAsUserFileIsNotUploaded() {
        run()
        val books = booksFolder()
        userFile(books, "own.epub", "the owner's copy, another size")
        userFile(drive.putFolder(books, "Серия"), "Том 2.epub", "t2")
        book("own.epub", "mine")
        book("Серия/Том 2.epub", "t2")
        book("fresh.epub", "f")
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = grantedRun(inbox = null)

        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk", "fresh.epub"), drive.uploads)
        assertEquals(1, drive.nodes.values.count { it.name == "own.epub" })
        assertEquals(1, drive.nodes.values.count { it.name == "Том 2.epub" })
        assertEquals(0, s.booksPending)
        assertEquals(6, s.booksLocal)
        assertEquals(6, s.booksOnDrive)
    }

    @Test fun changedBookReplacesItsOldCopyUnderTheReadonlyView() {
        run()
        val oldA = drive.files()["Nekoshelf/books/a.epub"]!!.id
        val a = book("a.epub", "aaaa-longer", mtime = 1_700_000_500_000L)
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = grantedRun(inbox = null)

        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk", "a.epub"), drive.uploads)
        assertTrue(oldA in drive.deletes)
        val copies = drive.nodes.values.filter { bookPathOf(it.props) == "a.epub" }
        assertEquals(1, copies.size)
        assertEquals("1700000500000", copies[0].props["mtime"])
        assertEquals(0, s.booksPending)
        assertEquals(3, s.booksOnDrive)

        // Restoring it brings the new version back.
        val to = File(tmp.root, "a.epub")
        runner(readonly = true).downloadBook(BookEntry("a.epub", a.length(), a.lastModified()), to)
        assertEquals("aaaa-longer", to.readText())
    }

    @Test fun checkboxOnWithoutReadonlyViewHoldsTheBooks() {
        store.update { it.copy(inboxEnabled = true) }

        val s = runner().run(charging, false) // no readonly view this run

        assertEquals(listOf("koreader-2026-10-23.zip", "manga-2026-10-23.tachibk"), drive.uploads)
        assertEquals(0, drive.treeCalls)
        assertEquals(3, s.booksLocal)
        assertEquals(3, s.booksPending)
        assertEquals(0, s.booksOnDrive)
        assertFalse(s.booksCaughtUpOnce)
        assertEquals(1, s.koreaderSnapshots)
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastError)
    }

    @Test fun caughtUpFlagIsLeftAloneWhileBooksAreHeld() {
        run()
        book("new.epub", "n")
        store.update { it.copy(inboxEnabled = true) }
        drive.uploads.clear()
        now = at(day0.plusDays(1))

        val s = runner().run(charging, false)

        assertTrue("new.epub" !in drive.uploads)
        assertEquals(1, s.booksPending)
        assertTrue(s.booksCaughtUpOnce)
    }

    @Test fun readonlyLostByTheListingHoldsTheBooksAndIsTheInboxError() {
        store.update { it.copy(inboxEnabled = true) }

        val s = runner(readonly = true, readonlyLost = { DriveAuthException("no drive.readonly token mid-run") }).run(charging, false)

        assertEquals(listOf("koreader-2026-10-23.zip", "manga-2026-10-23.tachibk"), drive.uploads)
        assertEquals(3, s.booksPending)
        assertFalse(s.booksCaughtUpOnce)
        assertEquals(INBOX_ERROR_NO_ACCESS, s.inboxError)
        assertEquals(now, s.lastSuccessAt)
        assertNull(s.lastError)

        // Play services failing for the readonly token mid-run: the same path, not a failed backup.
        drive.uploads.clear()
        now += 60_000
        val t = runner(readonly = true, readonlyLost = { TokenUnavailableException("Google Play services timed out", null) })
            .run(charging, false)
        assertEquals(listOf("koreader-2026-10-23.zip", "manga-2026-10-23.tachibk"), drive.uploads)
        assertEquals(3, t.booksPending)
        assertEquals("нет сети", t.inboxError)
        assertEquals(now, t.lastSuccessAt)
        assertNull(t.lastError)
    }

    @Test fun restoreLooksUpTheBooksFolderOncePerRunner() {
        run()
        val series = drive.putFolder(booksFolder(), "Серия")
        userFile(series, "Том 1.epub", "один")
        userFile(series, "Том 2.epub", "два")
        drive.childQueries.clear()
        val r = runner(readonly = true)

        r.downloadBook(BookEntry("Серия/Том 1.epub", 8, 1), File(tmp.root, "t1"))
        r.downloadBook(BookEntry("Серия/Том 2.epub", 6, 1), File(tmp.root, "t2"))

        assertEquals(1, drive.childQueries.count { it == ("root" to "Nekoshelf") })
        assertEquals(1, drive.childQueries.count { it.second == "books" })
        assertEquals("два", File(tmp.root, "t2").readText())
    }

    @Test fun inboxWorkerHoldingTheGuardCausesNoDuplicateUpload() {
        run()
        userFile(booksFolder(), "dropped.epub", "dropped")
        now = at(day0.plusDays(1))
        val landed = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The wake's inbox work holds the guard, its book already in place.
        drive.onUpload = { name ->
            if (name == INBOX_SEEN_FILE && Thread.currentThread().name == "inbox-worker") {
                landed.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        var workerResult: InboxResult? = null
        val worker = Thread({ workerResult = inbox().run() }, "inbox-worker")
        worker.start()
        assertTrue(landed.await(10, TimeUnit.SECONDS))
        assertTrue(File(home, "dropped.epub").exists())
        val downloads = drive.downloads.toList()

        val s = grantedRun() // its own inbox step finds the guard taken and goes on

        release.countDown()
        worker.join(10_000)
        assertEquals(1, workerResult!!.received)
        assertEquals(downloads, drive.downloads)
        assertTrue("dropped.epub" !in drive.uploads)
        assertEquals(1, drive.nodes.values.count { it.name == "dropped.epub" })
        assertEquals(now, s.lastSuccessAt)
        assertEquals(0, s.booksPending)
        assertEquals(4, s.booksLocal)
    }

    @Test fun countsIncludeUserFilesWhenGranted() {
        run(power = battery) // nothing uploaded: all three pending
        val books = booksFolder()
        userFile(books, "a.epub", "aaaa")
        val sub = drive.putFolder(books, "sub")
        userFile(sub, "b.pdf", "bbbbbb")
        userFile(books, "not-on-the-reader.epub", "x")
        now += 60_000

        val s = grantedRun(inbox = null, power = battery)

        assertEquals(3, s.booksLocal)
        assertEquals(2, s.booksOnDrive)
        assertEquals(1, s.booksPending)
        assertFalse(s.booksCaughtUpOnce)

        userFile(drive.putFolder(sub, "deep"), "c.fb2", "cc")
        now += 60_000
        val t = grantedRun(inbox = null, power = battery)
        assertEquals(3, t.booksOnDrive)
        assertEquals(0, t.booksPending)
        assertTrue(t.booksCaughtUpOnce)
        assertTrue(drive.nodes.values.none { it.props["kind"] == "book" })

        // Without the grant the user files are invisible: the books go up as before the inbox.
        drive.uploads.clear()
        now += 60_000
        run(power = battery)
        assertTrue(drive.uploads.containsAll(listOf("a.epub", "b.pdf", "c.fb2")))
    }

    @Test fun downloadBookFallsBackToPathWhenGranted() {
        run()
        userFile(drive.putFolder(booksFolder(), "Серия"), "Том 1.epub", "из инбокса")
        val r = runner(readonly = true)

        val to = File(tmp.root, "t1.epub")
        r.downloadBook(BookEntry("Серия/Том 1.epub", "из инбокса".toByteArray().size.toLong(), 1L), to)
        assertEquals("из инбокса", to.readText())

        // App copies are still found first.
        val b = File(tmp.root, "b.pdf")
        r.downloadBook(BookEntry("sub/b.pdf", 6, 1_700_000_000_000L), b)
        assertEquals("bbbbbb", b.readText())

        // Not on Drive at all: the plain not-found error, not the hint.
        val e = assertThrows(FileNotFoundException::class.java) { r.downloadBook(BookEntry("nope.epub", 1, 1), File(tmp.root, "nope")) }
        assertNotEquals(RESTORE_HINT_INBOX, e.message)
        assertFalse(File(tmp.root, "nope").exists())
    }

    @Test fun withoutGrantRestoreReportsHint() {
        run()
        userFile(booksFolder(), "inbox.epub", "i")

        val e = assertThrows(IOException::class.java) { runner().downloadBook(BookEntry("inbox.epub", 1, 1), File(tmp.root, "i")) }

        assertEquals("включите \"Забирать новые книги\" и повторите", e.message)
        assertEquals("включите \"Забирать новые книги\" и повторите", backupErrorText(e))
        assertEquals(0, drive.treeCalls)
    }

    @Test fun checkboxOffBackupUnchanged() {
        run()
        userFile(booksFolder(), "dropped.epub", "d")
        book("dropped.epub", "d")
        drive.uploads.clear()
        drive.downloads.clear()
        now = at(day0.plusDays(1))

        val s = run()

        assertEquals(0, drive.treeCalls)
        assertTrue(drive.downloads.isEmpty())
        // drive.file never sees the owner's file: the book goes up as it always did.
        assertEquals(listOf("koreader-2026-10-24.zip", "manga-2026-10-24.tachibk", "dropped.epub"), drive.uploads)
        assertEquals(0, s.booksPending)
        assertNull(s.inboxLastCheckAt)
        assertEquals(now, s.lastSuccessAt)
    }

    @Test fun backupUsesTheProvenRootBesideTheOwnersSameNamedFolder() {
        val mine = drive.putFolder(null, "Nekoshelf") // the owner's own, first in Drive's answer
        val app = drive.putFolder(null, "Nekoshelf")
        drive.put(drive.folder("koreader", app), "koreader-2026-10-20.zip", "z".toByteArray(), mapOf("kind" to "koreader"))

        val s = grantedRun(inbox = null)

        assertEquals(now, s.lastSuccessAt)
        assertTrue(drive.nodes.values.none { it.parent == mine })
        val zip = drive.nodes.values.single { it.name == "koreader-2026-10-23.zip" }
        assertEquals(app, drive.nodes[zip.parent]!!.parent)
        assertEquals(3, drive.filesIn("Nekoshelf/books").size)
    }

    @Test fun ambiguousRootsFailTheBackupVisibly() {
        drive.putFolder(null, "Nekoshelf")
        drive.putFolder(null, "Nekoshelf")

        val s = run()

        assertEquals(INBOX_ERROR_MANY_ROOTS, s.lastError)
        assertNull(s.lastSuccessAt)
        assertTrue(drive.uploads.isEmpty())
    }
}
