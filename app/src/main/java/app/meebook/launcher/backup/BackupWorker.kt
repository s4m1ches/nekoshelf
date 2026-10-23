package app.meebook.launcher.backup

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.meebook.launcher.backup.drive.DriveAuthException
import app.meebook.launcher.backup.drive.DriveRest
import app.meebook.launcher.backup.drive.GoogleAuth
import app.meebook.launcher.data.koreader.KoreaderPaths
import app.meebook.launcher.data.manga.MangaBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Runs one backup. Always succeeds as far as WorkManager is concerned: failures are recorded in the
 * [BackupStatus], and a retry/backoff would be the timed polling the app does not do; the next wake
 * tries again. While a restore offer or a restore holds backups it does nothing at all (not even ask
 * for a token). A null token means the owner must sign in again; a token that is merely unavailable
 * for now ([app.meebook.launcher.backup.drive.TokenUnavailableException]) is recorded as «нет сети».
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val manual = inputData.getBoolean(BackupTrigger.KEY_MANUAL, false)
        val store = BackupStore(app)
        if (store.load().restoreOfferPending) return@withContext Result.success()
        try {
            val token = tokenSource(app)
            if (token() == null) {
                fail(store, BACKUP_ERROR_AUTH, countsForAuto = !manual)
            } else {
                val power = { BackupTrigger.readPower(app) }
                runnerFactory(app, store, token).run(power(), manual, power)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(store, backupErrorText(e), countsForAuto = !manual && !isNetworkClass(e))
        }
        Result.success()
    }

    /** Records a failure outside the runner; [countsForAuto] adds it to today's failed automatic runs (R32). */
    private fun fail(store: BackupStore, text: String, countsForAuto: Boolean) {
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        store.update {
            val s = it.copy(lastFailureAt = now, lastError = text)
            if (countsForAuto) s.countFailedAuto(today) else s
        }
    }

    internal companion object {
        /** Test hooks: where the access token comes from, and how the runner is built. */
        var tokenSource: (Context) -> (() -> String?) = { GoogleAuth(it)::silentToken }
        var runnerFactory: (Context, BackupStore, () -> String?) -> BackupRunner = ::backupRunner
    }
}

/** KOReader's folder on the reader's storage. */
fun koreaderRoot(): File = File(Environment.getExternalStorageDirectory(), "koreader")

/** KOReader's books home folder (where the books and their `.sdr` folders live). Blocking. */
fun koreaderHomeDir(): File = KoreaderPaths(koreaderRoot()).homeDir()

/**
 * The runner on the real Drive, KOReader folders and «Манга» bridge, as the worker and the backup
 * screen (sign-in check, restore) both use it. [token] is asked for before every Drive call.
 */
fun backupRunner(app: Context, store: BackupStore, token: () -> String?): BackupRunner =
    BackupRunner(
        drive = DriveRest(token = { token() ?: throw DriveAuthException("token unavailable") }),
        store = store,
        koreaderRoot = koreaderRoot(),
        homeDir = ::koreaderHomeDir,
        mangaBackup = { MangaBridge(app).backup() },
        mangaAvailable = { MangaBridge(app).available() },
        cacheDir = app.cacheDir,
        clock = System::currentTimeMillis,
        zone = ZoneId.systemDefault(),
    )
