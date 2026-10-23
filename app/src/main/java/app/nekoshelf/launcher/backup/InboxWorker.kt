package app.nekoshelf.launcher.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one inbox check on its own (the wake's unmetered work and «Проверить сейчас»). Always
 * succeeds as far as WorkManager is concerned: failures are the inbox's status text, and a
 * retry/backoff would be the timed polling the app does not do; the next wake tries again.
 *
 * Does nothing while the checkbox is off, signed out or a restore holds the app. An automatic run
 * also re-checks at its start that the network is unmetered (it may have been queued long ago).
 * A missing `drive.readonly` token is a [DriveAuthException]; a sign-in failure (that, or a 401
 * from Drive) sets [BackupStatus.inboxNeedsReauth] — see [InboxRunner] and [recordInboxFailure].
 */
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val manual = inputData.getBoolean(BackupTrigger.KEY_MANUAL, false)
        val store = BackupStore(app)
        val status = store.load()
        if (!status.signedIn || !status.inboxEnabled || status.restoreOfferPending) return@withContext Result.success()
        if (!manual && !unmetered(app)) return@withContext Result.success()
        try {
            val token = BackupWorker.readonlyTokenSource(app)
            if (token() == null) {
                recordInboxFailure(store, DriveAuthException("no drive.readonly token"))
            } else {
                runnerFactory(app, store, token).run()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordInboxFailure(store, e)
        }
        Result.success()
    }

    internal companion object {
        /** Test hooks: how the runner is built, and the unmetered check. */
        var runnerFactory: (Context, BackupStore, () -> String?) -> InboxRunner = { _, store, token -> inboxRunner(store, token) }
        var unmetered: (Context) -> Boolean = ::isUnmetered
    }
}
