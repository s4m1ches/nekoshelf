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
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.ActivityResult
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

    /** The inbox's readonly authorization as the controller sees it. */
    private class FakeReadonly : ReadonlyAuth {
        var intent: PendingIntent? = null
        var granted = false
        var resultToken: String? = null
        var intentCalls = 0
        override fun intent(activity: Activity): PendingIntent? { intentCalls++; return intent }
        override fun tokenFromResult(data: Intent): String? = resultToken
        override fun granted(): Boolean = granted
        override fun token(): String? = if (granted) "readonly-token" else null
    }

    private val readonly = FakeReadonly()

    /** The readonly token source each runner the controller built was given (null: none). */
    private val runnerReadonly = ArrayList<(() -> String?)?>()

    private fun controller(
        state: HomeState,
        io: CoroutineDispatcher,
        activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).create().get(),
    ): BackupController {
        return BackupController(
            activity, state,
            BackupDeps(
                store = store,
                runner = { _, ro -> runnerReadonly += ro; runner(readonly = ro != null) },
                koreaderRoot = { ko },
                homeDir = { home },
                downloads = { downloads },
                io = io,
                readonly = readonly,
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

    private fun runner(readonly: Boolean = false): BackupRunner {
        val watched = object : Drive by fake {
            override fun download(id: String, to: File) {
                if (fake.nodes[id]?.props?.get("kind") == "book") pendingDuringDownload += store.load().restoreOfferPending
                fake.download(id, to)
            }
        }
        return BackupRunner(watched, store, ko, { home }, { ByteArrayInputStream(tachibk()) }, { true }, tmp.root, { now }, zone, readonly = readonly)
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

    // ---- «Забирать новые книги с Диска» ----------------------------------------------------------

    private fun inboxChecks() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupTrigger.INBOX_MANUAL_WORK_NAME).get().size
    private fun inboxOn() = store.load().inboxEnabled
    private fun consentUi() = PendingIntent.getActivity(context, 0, Intent("consent"), PendingIntent.FLAG_IMMUTABLE)

    @Test fun turningOnWhenAlreadyGrantedEnablesAndChecksAtOnce() {
        readonly.granted = true
        controller.setInboxEnabled(true); idle()
        assertTrue(inboxOn())
        assertEquals(1, inboxChecks())
        assertNull(state.backupUi.inboxMessage)
        assertFalse(state.backupUi.busy)
        assertEquals(true, state.backup?.inboxEnabled)
    }

    @Test fun turningOnWithoutTheGrantStaysOffWithTheError() {
        readonly.granted = false // no consent UI, and still no grant (Play services failing)
        controller.setInboxEnabled(true); idle()
        assertFalse(inboxOn())
        assertEquals(0, inboxChecks())
        assertEquals("нет доступа к Диску — включите заново", state.backupUi.inboxMessage)
        assertFalse(state.backupUi.busy)
    }

    @Test fun consentCancelledStaysOff() {
        readonly.intent = consentUi()
        controller.setInboxEnabled(true); idle()
        assertTrue("the consent UI is up: the backup screen stays open", controller.signInPending)
        assertFalse(inboxOn())

        controller.onReadonlyResult(ActivityResult(Activity.RESULT_CANCELED, null)); idle()
        assertFalse(controller.signInPending)
        assertFalse(inboxOn())
        assertEquals(0, inboxChecks())
        assertEquals("нет доступа к Диску — включите заново", state.backupUi.inboxMessage)
        assertFalse(state.backupUi.busy)
    }

    @Test fun consentGivenEnablesAndChecks() {
        readonly.intent = consentUi()
        controller.setInboxEnabled(true); idle()
        readonly.resultToken = "readonly-token"
        readonly.granted = true
        controller.onReadonlyResult(ActivityResult(Activity.RESULT_OK, Intent())); idle()
        assertTrue(inboxOn())
        assertEquals(1, inboxChecks())
        assertNull(state.backupUi.inboxMessage)
        assertFalse(state.backupUi.busy)
    }

    @Test fun consentWithoutTheReadonlyScopeStaysOff() {
        // Granular consent: a token came back, but the owner left drive.readonly unticked.
        readonly.intent = consentUi()
        controller.setInboxEnabled(true); idle()
        readonly.resultToken = "file-only-token"
        readonly.granted = false
        controller.onReadonlyResult(ActivityResult(Activity.RESULT_OK, Intent())); idle()
        assertFalse(inboxOn())
        assertEquals(0, inboxChecks())
        assertEquals("нет доступа к Диску — включите заново", state.backupUi.inboxMessage)
    }

    @Test fun needsReauthReRunsTheTurnOnFlowAndClearsIt() {
        store.update { it.copy(inboxEnabled = true, inboxNeedsReauth = true) }
        refresh()
        readonly.granted = true
        controller.setInboxEnabled(true); idle()
        assertEquals(1, readonly.intentCalls)
        assertTrue(inboxOn())
        assertFalse(store.load().inboxNeedsReauth)
        assertEquals(1, inboxChecks())
    }

    @Test fun turningOffStoresItWithoutAskingGoogle() {
        store.inboxEnabled(true)
        refresh()
        controller.setInboxEnabled(false); idle()
        assertFalse(inboxOn())
        assertEquals(0, readonly.intentCalls)
        assertEquals(0, inboxChecks())
        assertEquals(false, state.backup?.inboxEnabled)
    }

    @Test fun checkNowQueuesTheManualCheckOnlyWhenTheInboxCanRun() {
        refresh()
        controller.checkInboxNow(); idle()
        assertEquals("checkbox off", 0, inboxChecks())

        store.update { it.copy(inboxEnabled = true, inboxNeedsReauth = true) }
        refresh()
        controller.checkInboxNow(); idle()
        assertEquals("waiting for a re-enable", 0, inboxChecks())

        store.update { it.copy(inboxNeedsReauth = false) }
        refresh()
        state.backupUi = state.backupUi.copy(inboxWork = BackupWork.RUNNING)
        controller.checkInboxNow(); idle()
        assertEquals("a check is running", 0, inboxChecks())

        state.backupUi = state.backupUi.copy(inboxWork = BackupWork.IDLE)
        controller.checkInboxNow(); idle()
        assertEquals(1, inboxChecks())
    }

    @Test fun aQueuedInboxCheckDisablesTheButton() {
        val st = HomeState(ShelfTab.ALL)
        val ac = Robolectric.buildActivity(ComponentActivity::class.java).create()
        controller(st, Dispatchers.Unconfined, ac.get())
        ac.start()
        idle()
        assertEquals(BackupWork.IDLE, st.backupUi.inboxWork)
        BackupTrigger.checkInboxNow(context) // waits for a network the test WorkManager never reports
        repeat(20) { if (st.backupUi.inboxWork == BackupWork.IDLE) { Thread.sleep(50); idle() } }
        assertEquals(BackupWork.ENQUEUED, st.backupUi.inboxWork)
    }

    /** The `Nekoshelf/books` folder on the fake Drive. */
    private fun booksFolder(): String = fake.nodes.values.single { it.folder && it.name == "books" }.id

    /** Removes the backup's own copy of [name], so only a user-added file can bring it back. */
    private fun dropAppCopy(name: String) {
        fake.nodes.values.filter { it.name == name && it.props["kind"] == "book" }.map { it.id }.forEach { fake.nodes.remove(it) }
    }

    @Test fun restoreUsesTheReadonlyGrantWhenTheInboxIsOn() {
        // A book the inbox brought: on Drive only as the owner's own file.
        dropAppCopy("a.epub")
        fake.put(booksFolder(), "a.epub", "aaaa".toByteArray())
        File(home, "a.epub").delete()
        store.inboxEnabled(true)
        readonly.granted = true
        open()
        runnerReadonly.clear()
        controller.confirmRestore(); idle()
        assertEquals("aaaa", File(home, "a.epub").readText())
        assertEquals("Готово", state.backupUi.message)
        assertEquals(1, runnerReadonly.size)
        assertEquals("readonly-token", runnerReadonly.single()?.invoke())
    }

    @Test fun restoreWithoutTheInboxSaysToTurnItOn() {
        dropAppCopy("a.epub")
        fake.put(booksFolder(), "a.epub", "aaaa".toByteArray())
        File(home, "a.epub").delete()
        store.saveInboxSeen(setOf("u-old")) // the inbox was used here once
        open()
        runnerReadonly.clear()
        controller.confirmRestore(); idle()
        assertFalse(File(home, "a.epub").exists())
        assertTrue("no readonly token for the restore", runnerReadonly.isNotEmpty() && runnerReadonly.all { it == null })
        assertEquals("Восстановлено, но не удалось: 1 — включите \"Забирать новые книги\" и повторите", state.backupUi.message)
    }

    @Test fun restoreWhileTheInboxNeedsReauthSaysToTurnItOn() {
        dropAppCopy("a.epub")
        File(home, "a.epub").delete()
        store.update { it.copy(inboxEnabled = true, inboxNeedsReauth = true) }
        open()
        runnerReadonly.clear()
        controller.confirmRestore(); idle()
        assertTrue("no readonly token for the restore", runnerReadonly.isNotEmpty() && runnerReadonly.all { it == null })
        assertEquals("Восстановлено, но не удалось: 1 — включите \"Забирать новые книги\" и повторите", state.backupUi.message)
    }

    @Test fun restoreWithoutAnyInboxHistoryKeepsThePlainPartialText() {
        // Never used the inbox: turning it on would not bring the book back.
        dropAppCopy("a.epub")
        File(home, "a.epub").delete()
        open()
        controller.confirmRestore(); idle()
        assertEquals("Восстановлено, но не удалось: 1", state.backupUi.message)
    }

    @Test fun restoreOnAWipedReaderWithASeenFileOnDriveSaysToTurnItOn() {
        dropAppCopy("a.epub")
        File(home, "a.epub").delete()
        val root = fake.nodes.values.single { it.folder && it.name == "Nekoshelf" }.id
        fake.upload(root, "inbox-seen.json", File(tmp.root, "s.json").apply { writeText("""{"ids":["u1"]}""") }, mapOf("kind" to "inbox-seen"), null)
        open()
        controller.confirmRestore(); idle()
        assertEquals("Восстановлено, но не удалось: 1 — включите \"Забирать новые книги\" и повторите", state.backupUi.message)
    }

    @Test fun restoreWithTheInboxOnKeepsThePlainPartialText() {
        // Not on Drive at all, even with the grant: the hint would not help.
        dropAppCopy("a.epub")
        File(home, "a.epub").delete()
        store.inboxEnabled(true)
        readonly.granted = true
        open()
        controller.confirmRestore(); idle()
        assertEquals("Восстановлено, но не удалось: 1", state.backupUi.message)
    }
}
