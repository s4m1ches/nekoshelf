package app.nekoshelf.launcher.ui.backup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.nekoshelf.launcher.R
import app.nekoshelf.launcher.backup.BackupRunner
import app.nekoshelf.launcher.backup.BackupStatus
import app.nekoshelf.launcher.backup.BackupStore
import app.nekoshelf.launcher.backup.BackupTrigger
import app.nekoshelf.launcher.backup.BookNeedsInboxException
import app.nekoshelf.launcher.backup.RESTORE_HINT_INBOX
import app.nekoshelf.launcher.backup.RestoreFailedException
import app.nekoshelf.launcher.backup.RestorePlan
import app.nekoshelf.launcher.backup.applyRestore
import app.nekoshelf.launcher.backup.backupErrorText
import app.nekoshelf.launcher.backup.backupRunner
import app.nekoshelf.launcher.backup.drive.GoogleAuth
import app.nekoshelf.launcher.backup.koreaderHomeDir
import app.nekoshelf.launcher.backup.koreaderRoot
import app.nekoshelf.launcher.backup.planRestore
import app.nekoshelf.launcher.backup.saveIntoFolder
import app.nekoshelf.launcher.backup.showFailureMark
import app.nekoshelf.launcher.ui.home.HomeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * The inbox's `drive.readonly` authorization ([GoogleAuth] in the app). Everything but
 * [tokenFromResult] blocks: call it off the main thread.
 */
interface ReadonlyAuth {
    /** The consent UI that adds drive.readonly, or null when none is needed (or Play services fail). */
    fun intent(activity: Activity): PendingIntent?
    /** The access token in the result of the UI from [intent]; null when there is none. */
    fun tokenFromResult(data: Intent): String?
    /** Whether drive.readonly is granted now (any failure: no). */
    fun granted(): Boolean
    /** A drive.file + drive.readonly token without UI, as [GoogleAuth.silentToken] gives it. */
    fun token(): String?
}

/** What [BackupController] works on; tests swap in a fake Drive, temp folders and an inline dispatcher. */
class BackupDeps(
    val store: BackupStore,
    /**
     * A runner whose Drive asks [token] before every call; with [readonlyToken] (drive.file +
     * drive.readonly) it may also look at the owner's own files, see [backupRunner].
     */
    val runner: (token: () -> String?, readonlyToken: (() -> String?)?) -> BackupRunner,
    val koreaderRoot: () -> File,
    val homeDir: () -> File,
    val downloads: () -> File,
    val io: CoroutineDispatcher,
    val readonly: ReadonlyAuth,
) {
    companion object {
        fun real(app: Context): BackupDeps {
            val store = BackupStore(app)
            val auth = GoogleAuth(app)
            return BackupDeps(
                store = store,
                runner = { token, readonly -> backupRunner(app, store, token, readonly) },
                koreaderRoot = ::koreaderRoot,
                homeDir = ::koreaderHomeDir,
                downloads = { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) },
                io = Dispatchers.IO,
                readonly = object : ReadonlyAuth {
                    override fun intent(activity: Activity) = auth.readonlyIntent(activity)
                    override fun tokenFromResult(data: Intent) = auth.tokenFromResult(data)
                    override fun granted() = auth.hasReadonly()
                    override fun token() = auth.silentToken(withReadonly = true)
                },
            )
        }
    }
}

/**
 * The backup screen's side of [app.nekoshelf.launcher.MainActivity]: sign-in, «Сделать сейчас»,
 * the restore offer, restore and the «Манга» save; it also keeps [HomeState.backup] (and the
 * header's «☁!») and the work's state current. Everything that touches prefs, Play services,
 * Drive or files runs on [BackupDeps.io]; the main thread only swaps state.
 *
 * Ruling R28: `restoreOfferPending` is also the "hold automatic backups" flag for a whole restore
 * (the runner and the worker do nothing while it is set). «Восстановить…» sets it, remembering
 * the value before; it does not open while a backup is queued or running. Only an answer that ends
 * the matter clears it: «Не нужно», a fully completed restore, or an empty plan. A partial restore
 * keeps it set, so the offer stays up (R30). A failed plan, «Отмена», leaving the dialog, or a
 * restore that failed outright puts the remembered value back (a pending offer stays pending; a
 * restore started from the button releases the hold). Whenever the hold is released, a wake check
 * runs ([BackupTrigger.onWake]): the backup it held may be due. All flag writes go one at a time,
 * in call order ([writeFlag]).
 *
 * «Забирать новые книги с Диска» ([setInboxEnabled]): on asks Google for drive.readonly first (the
 * consent UI when needed, with the same launcher pattern as the sign-in) and is stored only once the
 * grant is there, then checks at once; a refusal leaves it off with «нет доступа к Диску — включите
 * заново». Off is only stored (the grant stays; the owner can revoke it in their Google account).
 *
 * Construct it in `onCreate` (it registers the sign-in and readonly-consent result launchers).
 */
class BackupController(
    private val activity: ComponentActivity,
    private val state: HomeState,
    private val deps: BackupDeps = BackupDeps.real(activity.applicationContext),
) : BackupActions {
    private val app: Context = activity.applicationContext
    private val store = deps.store
    private val io = deps.io
    private val auth = GoogleAuth(app)
    private val scope get() = activity.lifecycleScope

    /** The Google sign-in (or readonly consent) UI is up: the coming onStart must keep the backup screen open. */
    var signInPending = false
        private set

    /** The downloaded snapshot behind the open restore dialog (deleted when it closes). */
    private var restoreZip: File? = null
    /** Bumped whenever the restore dialog closes or reopens, so a stale plan is dropped. */
    private var restoreGen = 0
    /** While the restore dialog holds backups: the flag's value before «Восстановить…» (R28). */
    private var holdPrevious: Boolean? = null
    /** The plan behind the open restore dialog found inbox history ([BackupRunner.hasInboxHistory]). */
    private var restoreInboxHistory = false
    /** Serializes the offer/hold flag writes (FIFO), see [writeFlag]. */
    private val flagLock = Mutex()

    private val signInLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult(), ::onSignInResult)

    private val readonlyLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult(), ::onReadonlyResult)

    init {
        // Ruling R22: the unique work's state, while the activity is started.
        scope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var last = BackupWork.IDLE
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(BackupTrigger.WORK_NAME).collect { infos ->
                    val work = workOf(infos)
                    ui { copy(work = work) }
                    // A run finished: show what it recorded.
                    if (last == BackupWork.RUNNING && work != BackupWork.RUNNING) refreshStatus()
                    last = work
                }
            }
        }
        // The inbox checks (the wake's and «Проверить новые книги»), the same way.
        scope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val wm = WorkManager.getInstance(app)
                var last = BackupWork.IDLE
                combine(
                    wm.getWorkInfosForUniqueWorkFlow(BackupTrigger.INBOX_WORK_NAME),
                    wm.getWorkInfosForUniqueWorkFlow(BackupTrigger.INBOX_MANUAL_WORK_NAME),
                ) { wake, manual -> Triple(workOf(wake), workOf(manual), workOf(wake + manual)) }.collect { (wake, manual, work) ->
                    ui { copy(inboxWakeWork = wake, inboxManualWork = manual) }
                    // A check ended (even one too quick to be seen running): show what it recorded.
                    if (last != BackupWork.IDLE && work == BackupWork.IDLE) refreshStatus()
                    last = work
                }
            }
        }
    }

    /** Every onStart: reread the status; a restore dialog left open is dropped (unless signing in). */
    fun onStart() {
        if (!signInPending) closeRestore()
        refreshStatus()
    }

    /** The backup screen opens: no old message or dialog; fetch the offer's date if it is pending. */
    fun onShow() {
        closeRestore()
        if (!state.backupUi.busy) ui { copy(message = null, inboxMessage = null) }
        scope.launch {
            val s = loadStatus()
            if (s.restoreOfferPending && state.backupUi.offerDate == null) fetchOfferDate()
        }
    }

    /** Rereads the stored status (and the header's failure mark) off the main thread. */
    fun refreshStatus() {
        scope.launch { loadStatus() }
    }

    private suspend fun loadStatus(): BackupStatus {
        val s = withContext(io) { store.load() }
        state.backup = s
        state.backupFailing = showFailureMark(s, System.currentTimeMillis())
        return s
    }

    // ---- sign-in -------------------------------------------------------------------------------

    override fun signIn() {
        if (state.backupUi.busy) return
        ui { copy(busy = true, message = null) }
        scope.launch {
            val intent = withContext(io) { auth.signInIntent(activity) }
            if (intent != null) {
                signInPending = true
                try {
                    signInLauncher.launch(IntentSenderRequest.Builder(intent).build())
                } catch (e: Exception) {
                    Log.w(TAG, "sign-in UI failed to start", e)
                    signInPending = false
                    ui { copy(busy = false, message = text(R.string.backup_sign_in_failed)) }
                }
                return@launch
            }
            // No UI needed means a token is already available; Play services failing gives none.
            val token = withContext(io) {
                try {
                    auth.silentToken()
                } catch (e: Exception) {
                    Log.w(TAG, "silent token failed", e)
                    null
                }
            }
            afterToken(token)
        }
    }

    private fun onSignInResult(result: ActivityResult) {
        signInPending = false
        val data = result.data
        val token = if (result.resultCode == Activity.RESULT_OK && data != null) auth.tokenFromResult(data) else null
        scope.launch { afterToken(token) }
    }

    /**
     * A fresh sign-in first asks Drive whether earlier backups exist; only then is the account saved,
     * with the offer flag set *before* signedIn so no automatic run can slip in between. A failed
     * check leaves the owner signed out. A re-sign-in (access was revoked) asks nothing.
     */
    private suspend fun afterToken(token: String?) {
        if (token == null) {
            ui { copy(busy = false, message = text(R.string.backup_sign_in_failed)) }
            return
        }
        ui { copy(message = text(R.string.backup_checking_drive)) }
        val outcome = withContext(io) {
            try {
                val wasSignedIn = store.load().signedIn
                var date: LocalDate? = null
                if (!wasSignedIn) {
                    val runner = deps.runner({ token }, null)
                    val has = runner.hasRemoteBackups()
                    if (has) date = runner.latestSnapshotDate()
                    writeFlag { has }
                }
                store.signedIn(auth.account(), System.currentTimeMillis())
                Result.success(date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sign-in check failed", e)
                Result.failure(e)
            }
        }
        outcome.fold(
            onSuccess = { date ->
                ui { copy(busy = false, message = null, offerDate = date) }
            },
            onFailure = { e -> ui { copy(busy = false, message = backupErrorText(e, text(R.string.backup_check_failed))) } },
        )
        refreshStatus()
    }

    // ---- «Забирать новые книги с Диска» ---------------------------------------------------------

    /**
     * On (also the re-enable after a sign-in failure, while the stored flag may still be on): the
     * readonly consent UI if Google needs one; if not, the grant is checked (no UI can also mean Play
     * services failing). Off: stored only.
     */
    override fun setInboxEnabled(on: Boolean) {
        if (state.backupUi.busy) return
        ui { copy(inboxMessage = null) }
        if (!on) {
            scope.launch {
                withContext(io) { store.inboxEnabled(false) }
                loadStatus()
            }
            return
        }
        ui { copy(busy = true) }
        scope.launch {
            val intent = withContext(io) { deps.readonly.intent(activity) }
            if (intent != null) {
                signInPending = true
                try {
                    readonlyLauncher.launch(IntentSenderRequest.Builder(intent).build())
                } catch (e: Exception) {
                    Log.w(TAG, "readonly consent UI failed to start", e)
                    signInPending = false
                    afterReadonly(granted = false)
                }
                return@launch
            }
            afterReadonly(granted = withContext(io) { deps.readonly.granted() })
        }
    }

    /** The consent UI's answer. A token alone is not enough: granular consent may leave drive.readonly out. */
    internal fun onReadonlyResult(result: ActivityResult) {
        signInPending = false
        val data = result.data
        val token = if (result.resultCode == Activity.RESULT_OK && data != null) deps.readonly.tokenFromResult(data) else null
        scope.launch { afterReadonly(granted = token != null && withContext(io) { deps.readonly.granted() }) }
    }

    /** Granted: stored on (which clears the re-enable flag) and checked at once. Not: stays as it was, with the error. */
    private suspend fun afterReadonly(granted: Boolean) {
        if (!granted) {
            ui { copy(busy = false, inboxMessage = text(R.string.inbox_no_access)) }
            return
        }
        withContext(io) { store.inboxEnabled(true) }
        BackupTrigger.checkInboxNow(app)
        ui { copy(busy = false, inboxMessage = null) }
        loadStatus()
    }

    /** «Проверить новые книги»: only while the button is enabled ([inboxCheckEnabled]). */
    override fun checkInboxNow() {
        val status = state.backup ?: return
        if (!inboxCheckEnabled(status, state.backupUi)) return
        BackupTrigger.checkInboxNow(app)
    }

    // ---- backup now / the offer ----------------------------------------------------------------

    override fun backupNow() {
        if (state.backupUi.busy || state.backupUi.work != BackupWork.IDLE) return
        // While an offer (or a restore) is pending, a run would make the fresh reader's near-empty
        // snapshot the newest one. Unknown status counts as pending.
        if (state.backup?.restoreOfferPending != false) return
        ui { copy(message = null) }
        BackupTrigger.runNow(app)
    }

    override fun declineRestoreOffer() {
        if (state.backupUi.busy) return
        // Automatic backups were waiting for this answer.
        setOfferPending(false)
    }

    private fun fetchOfferDate() {
        scope.launch {
            val date = withContext(io) {
                try {
                    runner().latestSnapshotDate()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            if (date != null) ui { copy(offerDate = date) }
        }
    }

    // ---- restore -------------------------------------------------------------------------------

    override fun openRestore() {
        // Not while a backup is queued or running: a queued one starts as soon as the network is
        // back (e.g. Wi-Fi turned on to restore) and would snapshot a half-restored reader.
        if (state.backupUi.busy || state.restore != null || state.backupUi.work != BackupWork.IDLE) return
        ui { copy(message = null, restoring = true) }
        state.restore = RestoreUi(plan = null, mangaAvailable = false)
        val gen = ++restoreGen
        scope.launch {
            // Hold automatic backups for the whole restore (R28), remembering the value before.
            val previous = writeFlag { true }
            if (gen != restoreGen) { // closed while the flag was being set
                setOfferPending(previous)
                return@launch
            }
            holdPrevious = previous
            val dir = File(app.cacheDir, RESTORE_DIR)
            val zip = File(dir, "snapshot-$gen.zip")
            val result = withContext(io) {
                try {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val runner = runner()
                    val plan = if (runner.latestSnapshot(zip)) planRestore(zip, deps.koreaderRoot(), deps.homeDir()) else EMPTY_PLAN
                    // Only books may need the hint; a failed look just leaves it out.
                    restoreInboxHistory = plan.books > 0 && runCatching { runner.hasInboxHistory() }.getOrDefault(false)
                    Result.success(plan to (runner.latestMangaBackupFile() != null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "restore plan failed", e)
                    zip.delete()
                    Result.failure(e)
                }
            }
            if (gen != restoreGen) { // closed meanwhile; closeRestore gave the hold back
                withContext(io) { zip.delete() }
                return@launch
            }
            result.fold(
                onSuccess = { (plan, manga) ->
                    restoreZip = zip
                    state.restore = RestoreUi(plan, manga)
                    // Nothing missing on the reader: that answers the offer too.
                    if (plan.items.isEmpty()) {
                        holdPrevious = null
                        setOfferPending(false)
                    }
                },
                onFailure = { e ->
                    val p = holdPrevious ?: previous
                    holdPrevious = null
                    state.restore = null
                    ui { copy(message = backupErrorText(e, text(R.string.restore_plan_failed)), restoring = false) }
                    setOfferPending(p)
                },
            )
        }
    }

    /** «Отмена» / «Закрыть», Back, or leaving the screen: the dialog goes, the hold is given back. */
    override fun closeRestore() {
        if (state.restore == null && restoreZip == null && holdPrevious == null) return
        restoreGen++
        state.restore = null
        ui { copy(restoring = false) }
        holdPrevious?.let { setOfferPending(it) }
        holdPrevious = null
        val zip = restoreZip ?: return
        restoreZip = null
        scope.launch { withContext(io) { zip.delete() } }
    }

    /**
     * Ruling R26: on IO; the snapshot is deleted afterwards whatever happened. The hold stays for
     * the whole apply; a full restore clears the flag, a partial one keeps it so the offer stays up
     * (R30), an outright failure puts the previous value back (R28).
     *
     * With the inbox on (and needing no re-enable) the runner gets the readonly token, so a book the
     * inbox brought (on Drive only as the owner's own file) comes back too. Without it such books
     * fail with [BookNeedsInboxException] when the plan found inbox history (the checkbox on, a
     * seen set, or an app-made `inbox-seen.json` on Drive), and the partial text says to turn the
     * inbox on; with no history they are plainly not on Drive and the text says only how many failed.
     */
    override fun confirmRestore() {
        val plan = state.restore?.plan ?: return
        val zip = restoreZip ?: return
        if (state.backupUi.busy) return
        val previous = holdPrevious ?: false
        val history = restoreInboxHistory
        holdPrevious = null
        restoreGen++
        restoreZip = null
        state.restore = null
        ui { copy(busy = true, message = text(R.string.restore_working)) }
        scope.launch {
            // The flag afterwards: released by a full restore, kept by a partial one, as before otherwise.
            val (message, hold) = withContext(io) {
                val status = store.load()
                val grant = status.inboxEnabled && !status.inboxNeedsReauth
                try {
                    val runner = runner(readonly = if (grant) deps.readonly::token else null)
                    applyRestore(zip, plan) { book, to -> runner.downloadBook(book, to, inboxHistory = history) }
                    text(R.string.restore_done) to false
                } catch (e: RestoreFailedException) {
                    Log.w(TAG, "restore partly failed", e)
                    val partial = app.getString(R.string.restore_partial, e.failed)
                    val hint = !grant && e.causes.any { it is BookNeedsInboxException }
                    (if (hint) "$partial — $RESTORE_HINT_INBOX" else partial) to true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                    backupErrorText(e, text(R.string.restore_failed)) to previous
                } finally {
                    zip.delete()
                }
            }
            writeFlag { hold }
            ui { copy(busy = false, restoring = false, message = message) }
            loadStatus()
            if (!hold) {
                BackupTrigger.onWake(app) // released: the backup it held may be due
            } else if (state.backupUi.offerDate == null) {
                fetchOfferDate() // the offer card comes back after a partial restore
            }
        }
    }

    /**
     * Stores the offer/hold flag off the main thread, then rereads the status. Releasing the hold
     * runs a wake check: the automatic backup it held may be due now.
     */
    private fun setOfferPending(value: Boolean) {
        scope.launch {
            writeFlag { value }
            loadStatus()
            if (!value) BackupTrigger.onWake(app)
        }
    }

    /**
     * The only way the controller writes the offer/hold flag: one write at a time, in call order
     * (a [Mutex] is fair), each a single read-and-set in [BackupStore.update] — so a close's write
     * can never land after the next open's read (ledger T9). [change] maps the stored value to the
     * new one; returns the stored value from before.
     */
    private suspend fun writeFlag(change: (Boolean) -> Boolean): Boolean = flagLock.withLock {
        withContext(io) {
            var before = false
            store.update {
                before = it.restoreOfferPending
                it.copy(restoreOfferPending = change(before))
            }
            before
        }
    }

    /** Ruling R25: the newest `.tachibk` into Downloads under a free name, never over a file. */
    override fun saveMangaBackup() {
        if (state.restore == null || state.backupUi.busy) return
        state.restore = state.restore?.copy(message = text(R.string.manga_saving))
        ui { copy(busy = true) }
        scope.launch {
            val result = withContext(io) {
                try {
                    val runner = runner()
                    val file = runner.latestMangaBackupFile()
                    if (file == null) {
                        text(R.string.manga_none)
                    } else {
                        // Exactly the file named: downloaded by its id, not "the newest" looked up again.
                        val saved = saveIntoFolder(deps.downloads(), file.name) { tmp -> runner.download(file, tmp) }
                        MediaScannerConnection.scanFile(app, arrayOf(saved.path), null, null)
                        app.getString(R.string.manga_saved, saved.name)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "«Манга» backup save failed", e)
                    backupErrorText(e, text(R.string.manga_save_failed))
                }
            }
            // Shown in the dialog; if it was closed meanwhile, on the backup screen.
            val dialog = state.restore
            if (dialog != null) state.restore = dialog.copy(message = result)
            ui { if (dialog == null) copy(busy = false, message = result) else copy(busy = false) }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun runner(readonly: (() -> String?)? = null): BackupRunner = deps.runner(auth::silentToken, readonly)

    private fun workOf(infos: List<WorkInfo>): BackupWork = when {
        infos.any { it.state == WorkInfo.State.RUNNING } -> BackupWork.RUNNING
        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> BackupWork.ENQUEUED
        else -> BackupWork.IDLE
    }

    private inline fun ui(change: BackupUi.() -> BackupUi) {
        state.backupUi = state.backupUi.change()
    }

    private fun text(@StringRes id: Int): String = app.getString(id)

    private companion object {
        const val TAG = "Backup"
        const val RESTORE_DIR = "restore"
        val EMPTY_PLAN = RestorePlan(emptyList(), books = 0, sdr = 0, statsRestored = false)
    }
}
