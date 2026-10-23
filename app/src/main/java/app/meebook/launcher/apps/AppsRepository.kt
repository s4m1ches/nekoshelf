package app.meebook.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import java.text.Collator
import java.util.Locale

data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

/**
 * Installed launchable apps and the 5-slot dock (persisted in [prefs] as `dock_<i>`).
 * PackageManager calls are slow-ish: call from a background thread.
 */
class AppsRepository(context: Context, private val prefs: SharedPreferences) {
    private val appContext = context.applicationContext ?: context
    private val pm: PackageManager = appContext.packageManager

    /** The dock's apps in slot order; slots whose app (and default) is not installed are left out. */
    fun dock(): List<AppEntry> = dockSlots().filterNotNull()

    /** Always [DOCK_SIZE] entries; null = empty slot (neither the chosen nor the default app is installed). */
    fun dockSlots(): List<AppEntry?> {
        val defaults = defaultDock()
        return (0 until DOCK_SIZE).map { i ->
            val e = prefs.getString(key(i), null)?.let { entry(it) } ?: defaults.getOrNull(i)?.let { entry(it) }
            e?.let { it.copy(label = SHORT_LABELS[it.pkg] ?: it.label) }
        }
    }

    fun setDock(index: Int, pkg: String) {
        require(index in 0 until DOCK_SIZE) { "dock index $index" }
        prefs.edit().putString(key(index), pkg).apply()
    }

    /** Every app with a launcher activity except this one, sorted by label (Russian collation). */
    fun all(): List<AppEntry> {
        val collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != appContext.packageName }
            .mapNotNull { entry(it) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    internal fun defaultDock(): List<String> = listOf(
        KOREADER,
        MANGA,
        if (installed(EINKBRO)) EINKBRO else defaultBrowser() ?: EINKBRO,
        SETTINGS,
        PLAY,
    )

    private fun entry(pkg: String): AppEntry? = try {
        if (pm.getLaunchIntentForPackage(pkg) == null) {
            null
        } else {
            val info = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun installed(pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun defaultBrowser(): String? {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val def = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (def != null && def != "android") return def
        return pm.queryIntentActivities(view, 0).firstOrNull()?.activityInfo?.packageName
    }

    private fun key(i: Int) = "dock_$i"

    companion object {
        const val DOCK_SIZE = 5
        const val KOREADER = "org.koreader.launcher"
        const val MANGA = "app.komikku.meebook"
        const val EINKBRO = "info.plateaukao.einkbro"
        const val SETTINGS = "com.android.settings"
        const val PLAY = "com.android.vending"

        /** Short dock labels (spec: KOReader, «Манга», «Браузер», «Опции», Google Play). */
        private val SHORT_LABELS = mapOf(
            KOREADER to "KOReader",
            MANGA to "Манга",
            EINKBRO to "Браузер",
            SETTINGS to "Опции",
            PLAY to "Google Play",
        )
    }
}
