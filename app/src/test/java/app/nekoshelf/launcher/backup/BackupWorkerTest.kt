package app.nekoshelf.launcher.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.nekoshelf.launcher.backup.drive.TokenUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    private lateinit var context: Context
    private lateinit var store: BackupStore
    private val tokenSource0 = BackupWorker.tokenSource
    private val runnerFactory0 = BackupWorker.runnerFactory

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
        BackupWorker.runnerFactory = { _, _, _ -> throw SecurityException("no storage") }
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
        BackupWorker.runnerFactory = { _, _, _ -> throw AssertionError("no run while the hold is on") }
        assertEquals(ListenableWorker.Result.success(), runWorker(manual = true))
        assertEquals(0, asked)
        assertEquals(before, store.load())
    }

    @Test
    fun failedSetupCountsAsAFailedAutoAttemptButNotManual() {
        BackupWorker.tokenSource = { { "token" } }
        BackupWorker.runnerFactory = { _, _, _ -> throw SecurityException("no storage") }
        runWorker(manual = true)
        assertEquals(0, store.load().failedAutoAttempts)
        runWorker()
        assertEquals(1, store.load().failedAutoAttempts)
        assertNull(store.load().lastSuccessAt)
    }
}
