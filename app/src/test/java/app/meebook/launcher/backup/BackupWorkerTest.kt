package app.meebook.launcher.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<BackupWorker>(context).build().doWork() }

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
}
