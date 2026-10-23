package app.nekoshelf.launcher.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The header's quick toggles: Wi‑Fi, frontlight, battery.
 *
 * - Wi‑Fi: `WifiManager.setWifiEnabled` — works because the app targets API 28, except
 *   in airplane mode (system refuses; fall back to the Wi‑Fi panel).
 * - Frontlight: on the Meebook E6 `Settings.System.SCREEN_BRIGHTNESS` drives the
 *   frontlight (0 = off). Off stores the current level; on restores it (default 60).
 *   Needs the special `WRITE_SETTINGS` grant; without it [setLight] opens the system
 *   "modify system settings" screen for this app instead. See docs/device-notes.md.
 */
class Toggles(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("toggles", Context.MODE_PRIVATE)
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)

    fun wifiOn(): Boolean = wifi?.isWifiEnabled == true

    /**
     * Asks the system to switch Wi‑Fi; the state settles asynchronously (WIFI_STATE_CHANGED).
     * Returns false if the system refused — notably in **airplane mode**, where Android 11
     * lets only Settings toggle Wi‑Fi. The caller should then [openWifiPanel].
     */
    fun setWifi(on: Boolean): Boolean =
        try {
            @Suppress("DEPRECATION")
            wifi?.setWifiEnabled(on) == true
        } catch (e: SecurityException) {
            Log.w(TAG, "setWifiEnabled refused", e)
            false
        }

    /** The system Wi‑Fi quick panel (a bottom sheet with the switch), or Wi‑Fi settings before API 29. */
    fun openWifiPanel() = start(
        Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS),
    )

    fun lightOn(): Boolean = brightness() > 0

    /** Whether the app may write SCREEN_BRIGHTNESS (special app access, granted once by the owner). */
    fun canSetLight(): Boolean = Settings.System.canWrite(app)

    /**
     * Turns the frontlight on (last non-zero level, default [DEFAULT_LEVEL]) or off (0).
     * Returns false — and opens the WRITE_SETTINGS grant screen — if the app may not
     * write system settings yet.
     */
    fun setLight(on: Boolean): Boolean {
        if (!canSetLight()) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${app.packageName}")))
            return false
        }
        return try {
            val current = brightness()
            val cr = app.contentResolver
            if (on) {
                if (current == 0) {
                    val level = prefs.getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(1, 255)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level)
                }
            } else {
                if (current > 0) prefs.edit().putInt(KEY_LEVEL, current).apply()
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "brightness write failed", e)
            false
        }
    }

    /** Battery charge in percent (0–100), from the sticky ACTION_BATTERY_CHANGED; -1 if unknown. */
    fun battery(): Int {
        val intent = try {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    fun openWifiSettings() = start(Intent(Settings.ACTION_WIFI_SETTINGS))

    /**
     * Opens the system display settings («Экран» → «Яркость»). The firmware's own
     * warm/cold light panel lives in the pull-down shade and is not startable by apps.
     */
    fun openLightPanel() = start(Intent(Settings.ACTION_DISPLAY_SETTINGS))

    private fun brightness(): Int =
        try {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        } catch (_: Exception) {
            0
        }

    private fun start(intent: Intent) {
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    companion object {
        const val DEFAULT_LEVEL = 60
        private const val KEY_LEVEL = "light_level"
        private const val TAG = "Toggles"
    }
}
