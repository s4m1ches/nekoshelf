package app.nekoshelf.launcher.ui.backup

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.nekoshelf.launcher.backup.BackupRunner
import app.nekoshelf.launcher.backup.BackupStatus
import app.nekoshelf.launcher.backup.BackupStore
import app.nekoshelf.launcher.backup.BackupTrigger
import app.nekoshelf.launcher.backup.FakeDrive
import app.nekoshelf.launcher.backup.Power
import app.nekoshelf.launcher.backup.gunzipText
import app.nekoshelf.launcher.backup.tachibk
import android.content.Intent
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineDispatcher
import app.nekoshelf.launcher.backup.drive.Drive
import app.nekoshelf.launcher.domain.ShelfTab
import app.nekoshelf.launcher.ui.home.HomeState
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext

/**
 * Ruling R28: `restoreOfferPending` holds automatic backups for a whole restore and is cleared only
 * by an answer that ends the matter; «Сделать сейчас» does nothing while it is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var store: BackupStore
    private lateinit var fake: FakeDrive
    private lateinit var ko: File
    private lateinit var home: File
    private lateinit var downloads: File
    private lateinit var state: HomeState
    private lateinit var controller: BackupController

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = LocalDate.of(2026, 10, 23).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    /** The flag as the store held it at each book download (i.e. during apply). */
    private val pendingDuringDownload = ArrayList<Boolean>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        BackupTrigger.executor = Executor { it.run() }
        // The next day, battery full: a wake queues a run unless something holds it.
        BackupTrigger.clock = { now + 24 * 3_600_000L }
        BackupTrigger.zone = { zone }
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 90)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
        store = BackupStore(context)
        store.save(BackupStatus.EMPTY)
        store.signedIn("owner@example.com", now)
        fake = FakeDrive()
        ko = tmp.newFolder("koreader").also { File(it, "settings.reader.lua").writeText("return {}") }
        home = tmp.newFolder("Books")
        File(home, "a.epub").writeText("aaaa")
        File(home, "sub").mkdirs()
        File(home, "sub/b.pdf").writeText("bbbbbb")
        downloads = tmp.newFolder("Download")
        // One real backup on the fake Drive.
        runner().run(Power(50, charging = true), manual = true)
        setPending(false)

        state = HomeState(ShelfTab.ALL)
        controller = controller(state, Dispatchers.Unconfined)
    }

    private fun controller(state: HomeState, io: CoroutineDispatcher): BackupController {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        return BackupController(
            activity, state,
            BackupDeps(
                store = store,
                runner = { runner() },
                koreaderRoot = { ko },
                homeDir = { home },
                downloads = { downloads },
                io = io,
            ),
        )
    }

    private val savedExecutor = BackupTrigger.executor
    private val savedClock = BackupTrigger.clock
    private val savedZone = BackupTrigger.zone

    @After fun tearDown() {
        BackupTrigger.executor = savedExecutor
        BackupTrigger.clock = savedClock
        BackupTrigger.zone = savedZone
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** Runs the IO work it is handed only when told, newest first: the worst order for racing writes. */
    private class LifoDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }
    }

    private fun drain(io: LifoDispatcher) {
        repeat(1000) {
            idle()
            val task = io.tasks.removeLastOrNull() ?: return
            task.run()
        }
        error("IO work did not settle")
    }

    private fun queuedRuns() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupTrigger.WORK_NAME).get().size

    private fun runner(): BackupRunner {
        val watched = object : Drive by fake {
            override fun download(id: String, to: File) {
                if (fake.nodes[id]?.props?.get("kind") == "book") pendingDuringDownload += store.load().restoreOfferPending
                fake.download(id, to)
            }
        }
        return BackupRunner(watched, store, ko, { home }, { ByteArrayInputStream(tachibk()) }, { true }, tmp.root, { now }, zone)
    }

    private fun setPending(v: Boolean) = store.update { it.copy(restoreOfferPending = v) }
    private fun pending() = store.load().restoreOfferPending
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun refresh() { controller.refreshStatus(); idle() }
    private fun open() { controller.openRestore(); idle() }

    @Test fun planFailureKeepsAPendingOffer() {
        setPending(true)
        fake.failEverything = UnknownHostException("offline")
        open()
        assertTrue(pending())
        assertNull(state.restore)
        assertEquals("нет сети", state.backupUi.message)
        assertFalse(state.backupUi.restoring)
        assertEquals("the offer still holds backups", 0, queuedRuns())
    }

    @Test fun planFailureWithoutAKnownCauseSaysTheRestoreCouldNotBePrepared() {
        fake.failEverything = IllegalStateException("boom")
        open()
        assertEquals("не удалось подготовить восстановление", state.backupUi.message)
        assertFalse(pending())
    }

    @Test fun restoreDoesNotOpenWhileARunIsQueuedOrRunning() {
        for (work in listOf(BackupWork.ENQUEUED, BackupWork.RUNNING)) {
            state.backupUi = state.backupUi.copy(work = work)
            open()
            assertNull(state.restore)
            assertFalse(state.backupUi.restoring)
            assertFalse(pending())
        }
    }

    @Test fun closeThenReopenAtOnceKeepsTheHoldOn() {
        // Ledger T9: the close's write must not land after the reopen's read-and-set.
        val io = LifoDispatcher()
        val st = HomeState(ShelfTab.ALL)
        val c = controller(st, io)
        File(home, "a.epub").delete()
        c.openRestore(); drain(io)
        assertNotNull(st.restore?.plan)
        assertTrue(pending())

        c.closeRestore()
        c.openRestore()
        drain(io)

        assertNotNull(st.restore?.plan)
        assertTrue("held while the reopened dialog is up", pending())
        c.closeRestore(); drain(io)
        assertFalse("the button restore gives the hold back", pending())
    }

    @Test fun cancelKeepsAPendingOffer() {
        setPending(true)
        File(home, "a.epub").delete()
        open()
        assertEquals(1, state.restore?.plan?.books)
        assertTrue(pending())
        controller.closeRestore(); idle()
        assertTrue(pending())
        assertNull(state.restore)
        assertEquals("the offer still waits for an answer", 0, queuedRuns())
    }

    @Test fun completedRestoreClearsTheOfferAndHeldBackupsWhileApplying() {
        setPending(true)
        File(home, "a.epub").delete()
        open()
        controller.confirmRestore(); idle()
        assertEquals("aaaa", File(home, "a.epub").readText())
        assertEquals(listOf(true), pendingDuringDownload)
        assertFalse(pending())
        assertEquals("Готово", state.backupUi.message)
        assertFalse(state.backupUi.busy)
        assertEquals("released: the waiting backup is queued", 1, queuedRuns())
    }

    @Test fun partialRestoreKeepsTheOffer() {
        // R30: the reader is still half-restored; the offer stays until a full restore or «Не нужно».
        setPending(true)
        File(home, "a.epub").delete()
        open()
        fake.failEverything = UnknownHostException("offline")
        controller.confirmRestore(); idle()
        assertTrue(pending())
        assertEquals("Восстановлено, но не удалось: 1", state.backupUi.message)
        assertFalse(state.backupUi.restoring)
        assertEquals(0, queuedRuns())
    }

    @Test fun partialRestoreFromTheButtonAlsoKeepsTheHold() {
        File(home, "a.epub").delete()
        open()
        fake.failEverything = UnknownHostException("offline")
        controller.confirmRestore(); idle()
        assertTrue(pending())
        assertEquals(0, queuedRuns())
    }

    @Test fun emptyPlanClearsTheOffer() {
        setPending(true)
        open()
        assertEquals(0, state.restore?.plan?.items?.size)
        assertFalse(pending())
        controller.closeRestore(); idle()
        assertFalse(pending())
        assertEquals(1, queuedRuns())
    }

    @Test fun declineClearsTheOffer() {
        setPending(true)
        controller.declineRestoreOffer(); idle()
        assertFalse(pending())
        assertEquals(false, state.backup?.restoreOfferPending)
    }

    @Test fun restoreFromTheButtonHoldsBackupsAndCancelReleasesThem() {
        File(home, "a.epub").delete()
        open()
        assertNotNull(state.restore?.plan)
        assertTrue("held while the dialog is open", pending())
        controller.closeRestore(); idle()
        assertFalse(pending())
        assertEquals("released: a run held meanwhile may go now", 1, queuedRuns())
    }

    @Test fun restoreFromTheButtonHoldsBackupsWhileApplyingThenReleases() {
        File(home, "a.epub").delete()
        open()
        controller.confirmRestore(); idle()
        assertEquals(listOf(true), pendingDuringDownload)
        assertFalse(pending())
    }

    @Test fun leavingTheScreenGivesTheHoldBack() {
        File(home, "a.epub").delete()
        open()
        controller.onStart(); idle()
        assertNull(state.restore)
        assertFalse(pending())
    }

    @Test fun backupNowDoesNothingWhileTheOfferIsPending() {
        setPending(true)
        refresh()
        controller.backupNow(); idle()
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupTrigger.WORK_NAME).get().isEmpty())

        setPending(false)
        refresh()
        controller.backupNow(); idle()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupTrigger.WORK_NAME).get().size)
    }

    @Test fun mangaSaveWritesTheNamedFileIntoDownloads() {
        open()
        assertTrue(state.restore!!.mangaAvailable)
        controller.saveMangaBackup(); idle()
        val saved = File(downloads, "manga-2026-10-23.tachibk")
        assertEquals("tachibk", gunzipText(saved.readBytes()))
        assertEquals("Сохранено в Загрузки: manga-2026-10-23.tachibk", state.restore?.message)
        assertFalse(state.backupUi.busy)
    }

    @Test fun mangaSaveFailureWithoutAKnownCauseSaysWhatFailed() {
        open()
        fake.failEverything = IllegalStateException("boom")
        controller.saveMangaBackup(); idle()
        assertEquals("не удалось скачать бэкап Манги", state.restore?.message)
    }
}
