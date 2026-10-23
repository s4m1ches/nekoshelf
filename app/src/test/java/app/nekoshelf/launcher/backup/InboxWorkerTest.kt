package app.nekoshelf.launcher.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.DriveException
import app.nekoshelf.launcher.backup.drive.TokenUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class InboxWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: BackupStore
    private lateinit var home: File
    private val drive = FakeDrive()
    private val readonlySource0 = BackupWorker.readonlyTokenSource
    private val factory0 = InboxWorker.runnerFactory
    private val unmetered0 = InboxWorker.unmetered

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = BackupStore(context)
        store.save(BackupStatus.EMPTY)
        store.saveInboxSeen(emptySet())
        store.signedIn("a@b.c", 0L)
        store.update { it.copy(inboxEnabled = true) }
        home = tmp.newFolder("Books")
        BackupWorker.readonlyTokenSource = { { "readonly" } }
        InboxWorker.unmetered = { true }
        InboxWorker.runnerFactory = { _, s, _ -> InboxRunner(drive, s, { home }, { Long.MAX_VALUE }, { 1000L }) }
    }

    @After
    fun tearDown() {
        BackupWorker.readonlyTokenSource = readonlySource0
        InboxWorker.runnerFactory = factory0
        InboxWorker.unmetered = unmetered0
    }

    private fun runWorker(manual: Boolean = false): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<InboxWorker>(context)
            .setInputData(workDataOf(BackupTrigger.KEY_MANUAL to manual))
            .build()
            .doWork()
    }

    private fun bookOnDrive() {
        val root = drive.folder("Nekoshelf", null)
        val books = drive.folder("books", root)
        drive.put(books, "new.epub", "x".toByteArray(), mimeType = "application/epub+zip")
    }

    @Test
    fun runsTheInboxAndSucceeds() {
        bookOnDrive()
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(File(home, "new.epub").exists())
        assertEquals(1, store.load().inboxReceived)
        assertNull(store.load().inboxError)
    }

    @Test
    fun nullTokenRecordsLostAccessAndNeedsReauth() {
        BackupWorker.readonlyTokenSource = { { null } }
        assertEquals(ListenableWorker.Result.success(), runWorker(manual = true))
        val s = store.load()
        assertEquals(INBOX_ERROR_NO_ACCESS, s.inboxError)
        assertTrue(s.inboxNeedsReauth)
    }

    @Test
    fun tokenHiccupIsNoNetworkAndNotReauth() {
        BackupWorker.readonlyTokenSource = { { throw TokenUnavailableException("Google Play services timed out", null) } }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = store.load()
        assertEquals(BACKUP_ERROR_NO_NETWORK, s.inboxError)
        assertFalse(s.inboxNeedsReauth)
    }

    @Test
    fun unexpectedFailureIsRecordedAndSucceeds() {
        InboxWorker.runnerFactory = { _, _, _ -> throw SecurityException("no storage") }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(inboxErrorText(SecurityException()), store.load().inboxError)
        assertFalse(store.load().inboxNeedsReauth)
    }

    @Test
    fun authFailureSetsNeedsReauthButA403DoesNot() {
        bookOnDrive()
        drive.failListTree = DriveException(403, "insufficient scope")
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(INBOX_ERROR_NO_ACCESS, store.load().inboxError)
        assertFalse(store.load().inboxNeedsReauth)

        drive.failListTree = DriveAuthException("401")
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(INBOX_ERROR_NO_ACCESS, store.load().inboxError)
        assertTrue(store.load().inboxNeedsReauth)
    }

    @Test
    fun authFailureThrownBySetupAlsoSetsNeedsReauth() {
        InboxWorker.runnerFactory = { _, _, _ -> throw DriveAuthException("401") }
        runWorker()
        assertTrue(store.load().inboxNeedsReauth)
    }

    @Test
    fun autoRunOnMeteredNetworkDoesNothing() {
        bookOnDrive()
        InboxWorker.unmetered = { false }
        var asked = 0
        BackupWorker.readonlyTokenSource = { { asked++; "readonly" } }
        assertEquals(ListenableWorker.Result.success(), runWorker(manual = false))
        assertEquals(0, asked)
        assertFalse(File(home, "new.epub").exists())
        assertNull(store.load().inboxLastCheckAt)
    }

    @Test
    fun manualRunOnMeteredNetworkRuns() {
        bookOnDrive()
        InboxWorker.unmetered = { false }
        runWorker(manual = true)
        assertTrue(File(home, "new.epub").exists())
    }

    @Test
    fun checkboxOffOrHoldDoesNothing() {
        bookOnDrive()
        var asked = 0
        BackupWorker.readonlyTokenSource = { { asked++; "readonly" } }
        store.update { it.copy(inboxEnabled = false) }
        assertEquals(ListenableWorker.Result.success(), runWorker(manual = true))
        store.update { it.copy(inboxEnabled = true, restoreOfferPending = true) }
        assertEquals(ListenableWorker.Result.success(), runWorker(manual = true))
        assertEquals(0, asked)
        assertFalse(File(home, "new.epub").exists())
    }

    @Test
    fun cancellationIsRethrown() {
        InboxWorker.runnerFactory = { _, _, _ -> throw CancellationException("stop") }
        try {
            runWorker()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun recordedAuthLossSetsNeedsReauthAndReEnablingClearsIt() {
        // What the backup's inbox step (readonly token gone mid-run / missing) goes through.
        recordInboxFailure(store, DriveAuthException("no drive.readonly token mid-run"))
        assertTrue(store.load().inboxNeedsReauth)
        store.inboxEnabled(true)
        assertFalse(store.load().inboxNeedsReauth)
        recordInboxFailure(store, TokenUnavailableException("hiccup", null))
        assertFalse(store.load().inboxNeedsReauth)
        assertNotNull(store.load().inboxError)
    }

    @Test
    fun aRunThatGetsThroughClearsNeedsReauth() {
        store.update { it.copy(inboxNeedsReauth = true, inboxError = INBOX_ERROR_NO_ACCESS) }
        bookOnDrive()
        runWorker(manual = true)
        assertFalse(store.load().inboxNeedsReauth)
        assertNull(store.load().inboxError)
        // A non-auth failure (403) also completes without a sign-in failure.
        store.update { it.copy(inboxNeedsReauth = true) }
        drive.failListTree = DriveException(403, "insufficient scope")
        runWorker(manual = true)
        assertFalse(store.load().inboxNeedsReauth)
    }

    @Test
    fun aPerFile401SetsNeedsReauth() {
        val root = drive.folder("Nekoshelf", null)
        val books = drive.folder("books", root)
        val id = drive.put(books, "new.epub", "x".toByteArray(), mimeType = "application/epub+zip")
        drive.failDownload[id] = DriveAuthException("401")
        runWorker()
        assertTrue(store.load().inboxNeedsReauth)
        assertEquals(INBOX_ERROR_NO_ACCESS, store.load().inboxError)
    }
}
