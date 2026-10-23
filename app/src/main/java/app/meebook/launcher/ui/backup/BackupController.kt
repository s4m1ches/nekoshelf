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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate

/**
 * The backup screen's side of [app.meebook.launcher.MainActivity]: sign-in, «Сделать сейчас»,
 * the restore offer, restore and the «Манга» save; it also keeps [HomeState.backup] (and the
 * header's «☁!») and the work's state current. Everything that touches prefs, Play services,
 * Drive or files runs on Dispatchers.IO; the main thread only swaps state.
 *
 * Construct it in `onCreate` (it registers the sign-in result launcher).
 */
class BackupController(private val activity: ComponentActivity, private val state: HomeState) : BackupActions {
    private val app: Context = activity.applicationContext
    private val store = BackupStore(app)
    private val auth = GoogleAuth(app)
    private val scope get() = activity.lifecycleScope

    /** The Google sign-in UI is up: the coming onStart must keep the backup screen open. */
    var signInPending = false
        private set

    /** The downloaded snapshot behind the open restore dialog (deleted when it closes). */
    private var restoreZip: File? = null
    /** Bumped whenever the restore dialog closes or reopens, so a stale plan is dropped. */
    private var restoreGen = 0

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
        val s = withContext(Dispatchers.IO) { store.load() }
        state.backup = s
        state.backupFailing = showFailureMark(s, System.currentTimeMillis())
        return s
    }

    // ---- sign-in -------------------------------------------------------------------------------

    override fun signIn() {
        if (state.backupUi.busy) return
        ui { copy(busy = true, message = null) }
        scope.launch {
            val intent = withContext(Dispatchers.IO) { auth.signInIntent(activity) }
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
            // No UI needed means a token is already available (null also covers Play services failing).
            afterToken(withContext(Dispatchers.IO) { auth.silentToken() })
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
        val outcome = withContext(Dispatchers.IO) {
            try {
                val wasSignedIn = store.load().signedIn
                var date: LocalDate? = null
                if (!wasSignedIn) {
                    val runner = backupRunner(app, store) { token }
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
        ui { copy(message = null) }
        BackupTrigger.runNow(app)
    }

    override fun declineRestoreOffer() {
        if (state.backupUi.busy) return
        scope.launch {
            withContext(Dispatchers.IO) { store.update { it.copy(restoreOfferPending = false) } }
            refreshStatus()
            // Automatic backups were waiting for this answer.
            BackupTrigger.onWake(app)
        }
    }

    private fun fetchOfferDate() {
        scope.launch {
            val date = withContext(Dispatchers.IO) {
                try {
                    runner().latestSnapshotDate()
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
        ui { copy(message = null) }
        state.restore = RestoreUi(plan = null, mangaAvailable = false)
        val gen = ++restoreGen
        scope.launch {
            // Either answer to the offer clears it.
            if (state.backup?.restoreOfferPending == true) {
                withContext(Dispatchers.IO) { store.update { it.copy(restoreOfferPending = false) } }
                refreshStatus()
            }
            val dir = File(app.cacheDir, RESTORE_DIR)
            val zip = File(dir, "snapshot-$gen.zip")
            val result = withContext(Dispatchers.IO) {
                try {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val runner = runner()
                    val plan = if (runner.latestSnapshot(zip)) planRestore(zip, koreaderRoot(), koreaderHomeDir()) else EMPTY_PLAN
                    Result.success(plan to (runner.latestMangaBackupName() != null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "restore plan failed", e)
                    zip.delete()
                    Result.failure(e)
                }
            }
            if (gen != restoreGen) { // closed meanwhile
                withContext(Dispatchers.IO) { zip.delete() }
                return@launch
            }
            result.fold(
                onSuccess = { (plan, manga) ->
                    restoreZip = zip
                    state.restore = RestoreUi(plan, manga)
                },
                onFailure = { e ->
                    state.restore = null
                    ui { copy(message = backupErrorText(e)) }
                },
            )
        }
    }

    override fun closeRestore() {
        if (state.restore == null && restoreZip == null) return
        restoreGen++
        state.restore = null
        val zip = restoreZip ?: return
        restoreZip = null
        scope.launch { withContext(Dispatchers.IO) { zip.delete() } }
    }

    /** Ruling R26: on IO; the snapshot is deleted afterwards whatever happened. */
    override fun confirmRestore() {
        val plan = state.restore?.plan ?: return
        val zip = restoreZip ?: return
        if (state.backupUi.busy) return
        restoreGen++
        restoreZip = null
        state.restore = null
        ui { copy(busy = true, message = text(R.string.restore_working)) }
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val runner = runner()
                    applyRestore(zip, plan) { book, to -> runner.downloadBook(book, to) }
                    text(R.string.restore_done)
                } catch (e: RestoreFailedException) {
                    Log.w(TAG, "restore partly failed", e)
                    app.getString(R.string.restore_partial, e.failed)
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                    backupErrorText(e)
                } finally {
                    zip.delete()
                }
            }
            ui { copy(busy = false, message = message) }
        }
    }

    /** Ruling R25: the newest `.tachibk` into Downloads under a free name, never over a file. */
    override fun saveMangaBackup() {
        if (state.restore == null || state.backupUi.busy) return
        state.restore = state.restore?.copy(message = text(R.string.manga_saving))
        ui { copy(busy = true) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val runner = runner()
                    val name = runner.latestMangaBackupName()
                    if (name == null) {
                        text(R.string.manga_none)
                    } else {
                        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val saved = saveIntoFolder(downloads, name) { tmp ->
                            if (!runner.latestMangaBackup(tmp)) throw FileNotFoundException("no «Манга» backup on Drive")
                        }
                        MediaScannerConnection.scanFile(app, arrayOf(saved.path), null, null)
                        app.getString(R.string.manga_saved, saved.name)
                    }
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

    private fun runner(): BackupRunner = backupRunner(app, store, auth::silentToken)

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
