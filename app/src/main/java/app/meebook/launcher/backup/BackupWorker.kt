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
import java.time.ZoneId

/**
 * Runs one backup. Always succeeds as far as WorkManager is concerned: failures are recorded in the
 * [BackupStatus], and a retry/backoff would be the timed polling the app does not do; the next wake
 * tries again.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val manual = inputData.getBoolean(BackupTrigger.KEY_MANUAL, false)
        val store = BackupStore(app)
        try {
            val token = tokenSource(app)
            if (token() == null) {
                fail(store, BACKUP_ERROR_AUTH)
            } else {
                runnerFactory(app, store, token).run(BackupTrigger.readPower(app), manual)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(store, backupErrorText(e))
        }
        Result.success()
    }

    private fun fail(store: BackupStore, text: String) {
        val now = System.currentTimeMillis()
        store.update { it.copy(lastFailureAt = now, lastError = text) }
    }

    internal companion object {
        /** Test hooks: where the access token comes from, and how the runner is built. */
        var tokenSource: (Context) -> (() -> String?) = { GoogleAuth(it)::silentToken }
        var runnerFactory: (Context, BackupStore, () -> String?) -> BackupRunner = { app, store, token ->
            val root = File(Environment.getExternalStorageDirectory(), "koreader")
            BackupRunner(
                drive = DriveRest(token = { token() ?: throw DriveAuthException("token unavailable") }),
                store = store,
                koreaderRoot = root,
                homeDir = { KoreaderPaths(root).homeDir() },
                mangaBackup = { MangaBridge(app).backup() },
                cacheDir = app.cacheDir,
                clock = System::currentTimeMillis,
                zone = ZoneId.systemDefault(),
            )
        }
    }
}
