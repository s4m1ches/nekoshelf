package app.meebook.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.meebook.launcher.backup.BackupTrigger

/** Registers, once per process, the screen-on / unlock receiver that starts the daily backup check. */
class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val wake = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = BackupTrigger.onWake(context)
        }
        registerReceiver(wake, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }
}
