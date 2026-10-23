package app.meebook.launcher.backup

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTriggerTest {
    private lateinit var context: Context
    private lateinit var store: BackupStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        store = BackupStore(context)
        store.save(BackupStatus.EMPTY)
        store.signedIn("a@b.c", 0L)
        battery(percent = 90, charging = false)
    }

    private fun battery(percent: Int, charging: Boolean) {
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, percent)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(
                    BatteryManager.EXTRA_STATUS,
                    if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
                ),
        )
    }

    private fun work(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("backup").get()

    @Test
    fun wakeAbove70EnqueuesOnce() {
        BackupTrigger.onWake(context)
        BackupTrigger.onWake(context)
        val w = work()
        assertEquals(1, w.size)
        assertEquals(WorkInfo.State.ENQUEUED, w[0].state)
        assertTrue("auto" in w[0].tags)
    }

    @Test
    fun wakeAtLowBatteryDoesNothing() {
        battery(percent = 50, charging = false)
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
    }

    @Test
    fun wakeAtLowBatteryWhileChargingEnqueues() {
        battery(percent = 20, charging = true)
        BackupTrigger.onWake(context)
        assertEquals(1, work().size)
    }

    @Test
    fun runNowEnqueuesManual() {
        battery(percent = 10, charging = false)
        BackupTrigger.runNow(context)
        val w = work()
        assertEquals(1, w.size)
        assertTrue("manual" in w[0].tags)
    }

    @Test
    fun manualPressDuringAutoRunIsQueuedAfterIt() {
        BackupTrigger.onWake(context)
        BackupTrigger.runNow(context)
        val w = work()
        assertEquals(2, w.size)
        assertTrue(w.any { "auto" in it.tags } && w.any { "manual" in it.tags })
    }

    @Test
    fun alreadyDoneTodayDoesNothing() {
        store.update { it.copy(lastSuccessAt = System.currentTimeMillis()) }
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
    }

    @Test
    fun signedOutDoesNothing() {
        store.signedOut()
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
    }
}
