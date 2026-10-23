package app.nekoshelf.launcher.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.TokenUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: BackupStore
    private val tokenSource0 = BackupWorker.tokenSource
    private val runnerFactory0 = BackupWorker.runnerFactory
    private val readonlyTokenSource0 = BackupWorker.readonlyTokenSource

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = BackupStore(context)
        store.save(BackupStatus.EMPTY)
        store.signedIn("a@b.c", 0L)
    }

    @After
    fun tearDown() {
        BackupWorker.tokenSource = tokenSource0
        BackupWorker.runnerFactory = runnerFactory0
        BackupWorker.readonlyTokenSource = readonlyTokenSource0
    }

    private fun runWorker(manual: Boolean = false): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(workDataOf(BackupTrigger.KEY_MANUAL to manual))
            .build()
            .doWork()
    }

    @Test
    fun nullTokenRecordsAuthErrorAndSucceeds() {
        BackupWorker.tokenSource = { { null } }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = store.load()
        assertEquals(BACKUP_ERROR_AUTH, s.lastError)
        assertNotNull(s.lastFailureAt)
    }

    @Test
    fun throwingTokenSourceRecordsFailureAndSucceeds() {
        BackupWorker.tokenSource = { throw IllegalStateException("play services") }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = store.load()
        assertTrue(s.lastError != null)
        assertNotNull(s.lastFailureAt)
    }

    @Test
    fun throwingRunnerSetupRecordsFailureAndSucceeds() {
        BackupWorker.tokenSource = { { "token" } }
        BackupWorker.runnerFactory = { _, _, _, _ -> throw SecurityException("no storage") }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = store.load()
        assertEquals(backupErrorText(SecurityException()), s.lastError)
        assertNotNull(s.lastFailureAt)
    }

    @Test
    fun tokenHiccupIsNoNetworkNotRevokedAccess() {
        // A Play-services timeout must not stop automatic backups the way revoked access does (R21).
        BackupWorker.tokenSource = { { throw TokenUnavailableException("Google Play services timed out", null) } }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = store.load()
        assertEquals(BACKUP_ERROR_NO_NETWORK, s.lastError)
        assertNotNull(s.lastFailureAt)
        assertEquals(0, s.failedAutoAttempts)
    }

    @Test
    fun pendingRestoreHoldsTheRunBeforeAnyTokenIsAsked() {
        store.update { it.copy(restoreOfferPending = true) }
        val before = store.load()
        var asked = 0
        BackupWorker.tokenSource = { { asked++; "token" } }
        BackupWorker.runnerFactory = { _, _, _, _ -> throw AssertionError("no run while the hold is on") }
        assertEquals(ListenableWorker.Result.success(), runWorker(manual = true))
        assertEquals(0, asked)
        assertEquals(before, store.load())
    }

    /** Runs the worker with a factory that records the readonly token it got, then stops the run. */
    private fun readonlyGiven(): (() -> String?)? {
        var given: (() -> String?)? = { "not called" }
        BackupWorker.runnerFactory = { _, _, _, ro -> given = ro; throw SecurityException("stop here") }
        runWorker()
        return given
    }

    @Test
    fun inboxRunGetsTheReadonlyToken() {
        store.update { it.copy(inboxEnabled = true) }
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { "readonly" } }
        assertEquals("readonly", readonlyGiven()!!())
        assertNull(store.load().inboxError)
    }

    @Test
    fun missingReadonlyGrantRunsTheBackupWithoutTheInbox() {
        store.update { it.copy(inboxEnabled = true) }
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { null } }
        assertNull(readonlyGiven())
        assertEquals(INBOX_ERROR_NO_ACCESS, store.load().inboxError)
    }

    @Test
    fun readonlyTokenHiccupSkipsTheInboxOnlyForThisRun() {
        store.update { it.copy(inboxEnabled = true) }
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { throw TokenUnavailableException("Google Play services timed out", null) } }
        assertNull(readonlyGiven())
        assertEquals(BACKUP_ERROR_NO_NETWORK, store.load().inboxError)
    }

    @Test
    fun checkboxOffNeverAsksForReadonly() {
        var asked = 0
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { asked++; "readonly" } }
        assertNull(readonlyGiven())
        assertEquals(0, asked)
        assertNull(store.load().inboxError)
    }

    @Test
    fun failedSetupCountsAsAFailedAutoAttemptButNotManual() {
        BackupWorker.tokenSource = { { "token" } }
        BackupWorker.runnerFactory = { _, _, _, _ -> throw SecurityException("no storage") }
        runWorker(manual = true)
        assertEquals(0, store.load().failedAutoAttempts)
        runWorker()
        assertEquals(1, store.load().failedAutoAttempts)
        assertNull(store.load().lastSuccessAt)
    }

    // ---- the checkbox on without a readonly view (ruling I3) ------------------------------------

    private val drive = FakeDrive()

    /** A real runner on [drive] with one local book, readonly exactly when the worker hands it a readonly token. */
    private fun realRunner() {
        val ko = tmp.newFolder("koreader").also { File(it, "settings.reader.lua").writeText("return {}") }
        val home = tmp.newFolder("Books").also { File(it, "a.epub").writeText("aaaa") }
        val cache = tmp.newFolder("cache")
        BackupWorker.runnerFactory = { _, s, _, ro ->
            BackupRunner(
                drive, s, ko, { home }, { null }, { false }, cache, System::currentTimeMillis, ZoneId.systemDefault(),
                readonly = ro != null,
            )
        }
    }

    private fun snapshotUp() = drive.uploads.any { it.startsWith("koreader-") }

    @Test
    fun nullReadonlyTokenWithTheCheckboxOnUploadsNoBooks() {
        store.update { it.copy(inboxEnabled = true) }
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { null } }
        realRunner()

        runWorker(manual = true) // a manual run may always upload books: only the hold keeps them back

        assertTrue(snapshotUp())
        assertFalse("a.epub" in drive.uploads)
        val s = store.load()
        assertEquals(1, s.booksPending)
        assertEquals(INBOX_ERROR_NO_ACCESS, s.inboxError)
        assertNotNull(s.lastSuccessAt)
        assertNull(s.lastError)
    }

    @Test
    fun readonlyTokenUnavailableWithTheCheckboxOnUploadsNoBooks() {
        store.update { it.copy(inboxEnabled = true) }
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { throw TokenUnavailableException("Google Play services timed out", null) } }
        realRunner()

        runWorker(manual = true) // a manual run may always upload books: only the hold keeps them back

        assertTrue(snapshotUp())
        assertFalse("a.epub" in drive.uploads)
        assertEquals(BACKUP_ERROR_NO_NETWORK, store.load().inboxError)
        assertNull(store.load().lastError)
    }

    @Test
    fun checkboxOffUploadsBooksAsBefore() {
        BackupWorker.tokenSource = { { "file" } }
        BackupWorker.readonlyTokenSource = { { throw AssertionError("not asked") } }
        realRunner()

        runWorker(manual = true) // a manual run may always upload books: only the hold keeps them back

        assertTrue(snapshotUp())
        assertTrue("a.epub" in drive.uploads)
        assertEquals(0, store.load().booksPending)
        assertNull(store.load().inboxError)
    }

    @Test
    fun fallbackTokenSaysWhenTheReadonlyViewWasLost() {
        var readonly: () -> String? = { "ro" }
        val t = ReadonlyFallbackToken({ readonly() }, { "file" })
        assertEquals("ro", t())
        assertNull(t.lost)

        readonly = { null }
        assertEquals("file", t())
        assertTrue(t.lost is DriveAuthException)
        readonly = { "ro" }
        assertEquals("file", t()) // the view stays lost for the rest of the run

        val hiccup = TokenUnavailableException("Google Play services timed out", null)
        val u = ReadonlyFallbackToken({ throw hiccup }, { "file" })
        assertEquals("file", u())
        assertEquals(hiccup, u.lost)
    }
}
