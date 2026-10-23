package app.meebook.launcher.ui.backup

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.meebook.launcher.backup.BackupRunner
import app.meebook.launcher.backup.BackupStatus
import app.meebook.launcher.backup.BackupStore
import app.meebook.launcher.backup.BackupTrigger
import app.meebook.launcher.backup.FakeDrive
import app.meebook.launcher.backup.Power
import app.meebook.launcher.backup.gunzipText
import app.meebook.launcher.backup.tachibk
import app.meebook.launcher.backup.drive.Drive
import app.meebook.launcher.domain.ShelfTab
import app.meebook.launcher.ui.home.HomeState
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

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        state = HomeState(ShelfTab.ALL)
        controller = BackupController(
            activity, state,
            BackupDeps(
                store = store,
                runner = { runner() },
                koreaderRoot = { ko },
                homeDir = { home },
                downloads = { downloads },
                io = Dispatchers.Unconfined,
            ),
        )
    }

    private val savedExecutor = BackupTrigger.executor

    @After fun tearDown() {
        BackupTrigger.executor = savedExecutor
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

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
    }

    @Test fun partialRestoreCountsAsCompleted() {
        setPending(true)
        File(home, "a.epub").delete()
        open()
        fake.failEverything = UnknownHostException("offline")
        controller.confirmRestore(); idle()
        assertFalse(pending())
        assertEquals("Восстановлено, но не удалось: 1", state.backupUi.message)
    }

    @Test fun emptyPlanClearsTheOffer() {
        setPending(true)
        open()
        assertEquals(0, state.restore?.plan?.items?.size)
        assertFalse(pending())
        controller.closeRestore(); idle()
        assertFalse(pending())
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
}
