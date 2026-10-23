package app.meebook.launcher.backup

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.concurrent.Executor
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTriggerTest {
    private lateinit var context: Context
    private lateinit var store: BackupStore
    private val executor0 = BackupTrigger.executor
    private val clock0 = BackupTrigger.clock
    private val zone0 = BackupTrigger.zone

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BackupTrigger.executor = Executor { it.run() }
        BackupTrigger.zone = { ZoneId.of("UTC") }
        BackupTrigger.clock = { NOON }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        store = BackupStore(context)
        store.save(BackupStatus.EMPTY)
        store.signedIn("a@b.c", 0L)
        battery(percent = 90, charging = false)
    }

    @After
    fun tearDown() {
        BackupTrigger.executor = executor0
        BackupTrigger.clock = clock0
        BackupTrigger.zone = zone0
        WorkManagerTestInitHelper.closeWorkDatabase()
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
        store.update { it.copy(lastSuccessAt = NOON - 3_600_000L) }
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
    }

    @Test
    fun signedOutDoesNothing() {
        store.signedOut()
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
    }

    @Test
    fun authFailureStopsAutoRunsUntilSignIn() {
        store.update { it.copy(lastFailureAt = 1L, lastError = BACKUP_ERROR_AUTH) }
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
        store.signedIn("a@b.c", NOON)
        BackupTrigger.onWake(context)
        assertEquals(1, work().size)
    }

    @Test
    fun wakeDoesItsWorkOnTheExecutor() {
        val queued = ArrayList<Runnable>()
        BackupTrigger.executor = Executor { queued += it }
        BackupTrigger.onWake(context)
        assertTrue(work().isEmpty())
        queued.forEach { it.run() }
        assertEquals(1, work().size)
    }

    private companion object {
        /** 2026-10-23 12:00 UTC. */
        const val NOON = 1_792_756_800_000L
    }
}
