package app.nekoshelf.launcher.backup

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * When a backup is started. Never on a timer: only a wake (screen on / unlock / boot / the home
 * screen coming up) or the owner's «Сделать сейчас». Both just enqueue unique work [WORK_NAME];
 * the work needs a network (it never turns Wi-Fi on) and the worker does the real run.
 */
object BackupTrigger {
    const val WORK_NAME = "backup"
    const val KEY_MANUAL = "manual"
    const val TAG_AUTO = "auto"
    const val TAG_MANUAL = "manual"

    /** Single background thread for the wake check (prefs read, sticky intent, enqueue); tests swap in an immediate one. */
    internal var executor: Executor = Executors.newSingleThreadExecutor()
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    /**
     * Cheap and safe to call often (returns at once; the check runs on a background thread): one prefs
     * read + one sticky intent, then at most an enqueue. KEEP leaves a run that is already queued or
     * running alone. A queued auto run may start later, when the network appears; at its start the
     * worker and the runner re-check only the restore hold (a held run does nothing), which is
     * intended. After an auth failure auto runs wait for a new sign-in ([BackupStore.signedIn]
     * clears the error). Also called when a restore releases the hold.
     */
    fun onWake(context: Context) {
        val app = context.applicationContext
        executor.execute { wake(app) }
    }

    private fun wake(app: Context) {
        val status = BackupStore(app).load()
        if (status.lastError == BACKUP_ERROR_AUTH) return
        val z = zone()
        val today = Instant.ofEpochMilli(clock()).atZone(z).toLocalDate()
        if (!shouldRunAuto(status, today, readPower(app), z)) return
        enqueue(app, manual = false, ExistingWorkPolicy.KEEP)
    }

    /** «Сделать сейчас»: queued after any run in progress, so the press is not swallowed by it. */
    fun runNow(context: Context) {
        enqueue(context.applicationContext, manual = true, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(app: Context, manual: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KEY_MANUAL to manual))
            .addTag(if (manual) TAG_MANUAL else TAG_AUTO)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    /** Battery level and charging state from the sticky `ACTION_BATTERY_CHANGED`; unknown level counts as 0. */
    internal fun readPower(context: Context): Power {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return Power(0, false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Power(percent, charging)
    }
}
