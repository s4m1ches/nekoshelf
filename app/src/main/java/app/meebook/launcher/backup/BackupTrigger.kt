package app.meebook.launcher.backup

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

    /**
     * Cheap and safe to call often: one prefs read + one sticky intent, then at most an enqueue.
     * KEEP leaves a run that is already queued or running alone.
     */
    fun onWake(context: Context) {
        val app = context.applicationContext
        val status = BackupStore(app).load()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
        if (!shouldRunAuto(status, today, readPower(app), zone)) return
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
