package app.meebook.launcher.device

import android.content.Context
import android.util.Log

/** Haoqing e-ink controls. */
object Eink {
    /**
     * One full-screen e-ink refresh (the black/white flash that clears ghosting).
     * Calls `android.os.EinkManager.sendOneFullFrame()` on the firmware's "eink" system
     * service via reflection; on any other device, or on any failure, does nothing.
     */
    fun fullRefresh(context: Context) {
        try {
            @Suppress("WrongConstant")
            val service = context.getSystemService("eink") ?: return
            service.javaClass.getMethod("sendOneFullFrame").invoke(service)
        } catch (t: Throwable) {
            Log.w("Eink", "full refresh unavailable", t)
        }
    }
}
