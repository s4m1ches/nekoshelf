package app.nekoshelf.launcher.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot the device is awake: treat it as a wake. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BackupTrigger.onWake(context)
    }
}
