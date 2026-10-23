package app.nekoshelf.launcher.device

import android.app.Activity
import android.util.Log

/** Haoqing e-ink controls. */
object Eink {
    /**
     * One full-screen e-ink refresh (the black/white flash that clears ghosting) of [activity]'s window.
     *
     * On this firmware `android.os.EinkManager.sendOneFullFrame()` (the "eink" system service, via
     * reflection) does not flash the current content: it marks the NEXT posted frame as a full
     * (GC16) update. So after the call the whole window is invalidated, which makes the view
     * system draw and post a new frame; a second invalidate on the next vsync guarantees one even
     * if a draw was already in flight. On any other device, or on any failure, it only redraws.
     */
    fun fullRefresh(activity: Activity) {
        try {
            @Suppress("WrongConstant")
            val service = activity.getSystemService("eink")
            service?.javaClass?.getMethod("sendOneFullFrame")?.invoke(service)
        } catch (t: Throwable) {
            Log.w("Eink", "full refresh unavailable", t)
        }
        val root = activity.window?.decorView ?: return
        root.invalidate()
        root.postInvalidateOnAnimation()
    }
}
