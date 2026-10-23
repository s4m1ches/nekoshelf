package app.meebook.launcher.ui.backup

import android.app.Activity
import android.content.Context
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
import app.meebook.launcher.R
import app.meebook.launcher.backup.BackupRunner
import app.meebook.launcher.backup.BackupStatus
import app.meebook.launcher.backup.BackupStore
import app.meebook.launcher.backup.BackupTrigger
import app.meebook.launcher.backup.RestoreFailedException
import app.meebook.launcher.backup.RestorePlan
import app.meebook.launcher.backup.applyRestore
import app.meebook.launcher.backup.backupErrorText
import app.meebook.launcher.backup.backupRunner
import app.meebook.launcher.backup.drive.GoogleAuth
import app.meebook.launcher.backup.koreaderHomeDir
import app.meebook.launcher.backup.koreaderRoot
import app.meebook.launcher.backup.planRestore
import app.meebook.launcher.backup.saveIntoFolder
import app.meebook.launcher.backup.showFailureMark
import app.meebook.launcher.ui.home.HomeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** What [BackupController] works on; tests swap in a fake Drive, temp folders and an inline dispatcher. */
class BackupDeps(
    val store: BackupStore,
    /** A runner whose Drive asks [token] before every call. */
    val runner: (token: () -> String?) -> BackupRunner,
    val koreaderRoot: () -> File,
    val homeDir: () -> File,
    val downloads: () -> File,
    val io: CoroutineDispatcher,
) {
    companion object {
        fun real(app: Context): BackupDeps {
            val store = BackupStore(app)
            return BackupDeps(
                store = store,
                runner = { token -> backupRunner(app, store, token) },
                koreaderRoot = ::koreaderRoot,
                homeDir = ::koreaderHomeDir,
                downloads = { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) },
                io = Dispatchers.IO,
            )
        }
    }
}

/**
 * The backup screen's side of [app.meebook.launcher.MainActivity]: sign-in, «Сделать сейчас»,
 * the restore offer, restore and the «Манга» save; it also keeps [HomeState.backup] (and the
 * header's «☁!») and the work's state current. Everything that touches prefs, Play services,
 * Drive or files runs on [BackupDeps.io]; the main thread only swaps state.
 *
 * Ruling R28: `restoreOfferPending` is also the "hold automatic backups" flag for a whole restore.
 * «Восстановить…» sets it, remembering the value before. Only an answer that ends the matter
 * clears it: «Не нужно», a restore that completed (fully or partly), or an empty plan. A failed
 * plan, «Отмена», leaving the dialog, or a restore that failed outright puts the remembered value
 * back (a pending offer stays pending; a restore started from the button releases the hold).
 *
 * Construct it in `onCreate` (it registers the sign-in result launcher).
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

    /** The Google sign-in UI is up: the coming onStart must keep the backup screen open. */
    var signInPending = false
        private set

    /** The downloaded snapshot behind the open restore dialog (deleted when it closes). */
    private var restoreZip: File? = null
    /** Bumped whenever the restore dialog closes or reopens, so a stale plan is dropped. */
    private var restoreGen = 0
    /** While the restore dialog holds backups: the flag's value before «Восстановить…» (R28). */
    private var holdPrevious: Boolean? = null

    private val signInLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult(), ::onSignInResult)

    init {
        // Ruling R22: the unique work's state, while the activity is started.
        scope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var last = BackupWork.IDLE
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(BackupTrigger.WORK_NAME).collect { infos ->
                    val work = when {
                        infos.any { it.state == WorkInfo.State.RUNNING } -> BackupWork.RUNNING
                        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> BackupWork.ENQUEUED
                        else -> BackupWork.IDLE
                    }
                    ui { copy(work = work) }
                    // A run finished: show what it recorded.
                    if (last == BackupWork.RUNNING && work != BackupWork.RUNNING) refreshStatus()
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
        if (!state.backupUi.busy) ui { copy(message = null) }
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
                    val runner = deps.runner { token }
                    val has = runner.hasRemoteBackups()
                    if (has) date = runner.latestSnapshotDate()
                    store.update { it.copy(restoreOfferPending = has) }
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
            onFailure = { e -> ui { copy(busy = false, message = backupErrorText(e)) } },
        )
        refreshStatus()
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
        scope.launch {
            withContext(io) { store.update { it.copy(restoreOfferPending = false) } }
            loadStatus()
            // Automatic backups were waiting for this answer.
            BackupTrigger.onWake(app)
        }
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
        if (state.backupUi.busy || state.restore != null) return
        ui { copy(message = null, restoring = true) }
        state.restore = RestoreUi(plan = null, mangaAvailable = false)
        val gen = ++restoreGen
        scope.launch {
            // Hold automatic backups for the whole restore (R28), remembering the value before.
            val previous = withContext(io) {
                val p = store.load().restoreOfferPending
                store.update { it.copy(restoreOfferPending = true) }
                p
            }
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
                    ui { copy(message = backupErrorText(e), restoring = false) }
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
     * the whole apply; a completed restore (also a partial one) clears the flag, an outright
     * failure puts the previous value back (R28).
     */
    override fun confirmRestore() {
        val plan = state.restore?.plan ?: return
        val zip = restoreZip ?: return
        if (state.backupUi.busy) return
        val previous = holdPrevious ?: false
        holdPrevious = null
        restoreGen++
        restoreZip = null
        state.restore = null
        ui { copy(busy = true, message = text(R.string.restore_working)) }
        scope.launch {
            val (message, completed) = withContext(io) {
                try {
                    val runner = runner()
                    applyRestore(zip, plan) { book, to -> runner.downloadBook(book, to) }
                    text(R.string.restore_done) to true
                } catch (e: RestoreFailedException) {
                    Log.w(TAG, "restore partly failed", e)
                    app.getString(R.string.restore_partial, e.failed) to true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                    backupErrorText(e) to false
                } finally {
                    zip.delete()
                }
            }
            withContext(io) { store.update { it.copy(restoreOfferPending = if (completed) false else previous) } }
            ui { copy(busy = false, restoring = false, message = message) }
            loadStatus()
        }
    }

    /** Stores the offer/hold flag off the main thread, then rereads the status. */
    private fun setOfferPending(value: Boolean) {
        scope.launch {
            withContext(io) { store.update { it.copy(restoreOfferPending = value) } }
            loadStatus()
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
                    backupErrorText(e)
                }
            }
            // Shown in the dialog; if it was closed meanwhile, on the backup screen.
            val dialog = state.restore
            if (dialog != null) state.restore = dialog.copy(message = result)
            ui { if (dialog == null) copy(busy = false, message = result) else copy(busy = false) }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun runner(): BackupRunner = deps.runner(auth::silentToken)

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
