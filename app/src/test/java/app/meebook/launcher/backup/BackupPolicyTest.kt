package app.meebook.launcher.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class BackupPolicyTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 23)
    private val hour = 3_600_000L
    private val signedIn = BackupStatus.EMPTY.copy(signedIn = true, account = "a@b.c")

    private fun ms(date: LocalDate, h: Int = 12) =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + h * hour

    @Test
    fun runsOnceADayAbove70OrCharging() {
        assertFalse(shouldRunAuto(signedIn, today, Power(70, false), zone))
        assertTrue(shouldRunAuto(signedIn, today, Power(71, false), zone))
        assertTrue(shouldRunAuto(signedIn, today, Power(10, true), zone))
        val done = signedIn.copy(lastSuccessAt = ms(today, 1))
        assertFalse(shouldRunAuto(done, today, Power(100, true), zone))
        val yesterday = signedIn.copy(lastSuccessAt = ms(today.minusDays(1), 23))
        assertTrue(shouldRunAuto(yesterday, today, Power(100, false), zone))
    }

    @Test
    fun holdsWhileRestoreOfferPending() {
        val s = signedIn.copy(restoreOfferPending = true)
        assertFalse(shouldRunAuto(s, today, Power(100, true), zone))
    }

    @Test
    fun firstSyncBooksStillGoUpOnTheChargerAfterTodaysBackup() {
        // R31: books waiting for the first full sync upload only while charging; a backup on
        // battery earlier today must not shut them out until tomorrow.
        val done = signedIn.copy(lastSuccessAt = ms(today, 1), booksPending = 3)
        assertTrue(shouldRunAuto(done, today, Power(20, true), zone))
        assertFalse(shouldRunAuto(done, today, Power(100, false), zone))
        assertFalse(shouldRunAuto(done.copy(booksPending = 0), today, Power(20, true), zone))
        assertFalse(shouldRunAuto(done.copy(booksCaughtUpOnce = true), today, Power(20, true), zone))
        // Still subject to the hold and the attempt cap.
        assertFalse(shouldRunAuto(done.copy(restoreOfferPending = true), today, Power(20, true), zone))
        val capped = done.copy(failedAutoDay = today.toString(), failedAutoAttempts = 3)
        assertFalse(shouldRunAuto(capped, today, Power(20, true), zone))
    }

    @Test
    fun threeFailedAutoAttemptsWaitForTheNextDay() {
        var s = signedIn
        repeat(2) { s = s.countFailedAuto(today) }
        assertEquals(today.toString(), s.failedAutoDay)
        assertEquals(2, s.failedAutoAttempts)
        assertTrue(shouldRunAuto(s, today, Power(100, false), zone))
        s = s.countFailedAuto(today)
        assertFalse(shouldRunAuto(s, today, Power(100, true), zone))
        // A new day starts the count again.
        val tomorrow = today.plusDays(1)
        assertTrue(shouldRunAuto(s, tomorrow, Power(100, false), zone))
        val next = s.countFailedAuto(tomorrow)
        assertEquals(tomorrow.toString(), next.failedAutoDay)
        assertEquals(1, next.failedAutoAttempts)
    }

    @Test
    fun notSignedInNeverRuns() {
        assertFalse(shouldRunAuto(BackupStatus.EMPTY, today, Power(100, true), zone))
    }

    @Test
    fun firstBookSyncOnlyWhileCharging() {
        assertFalse(mayUploadBooks(signedIn, Power(100, false), manual = false))
        assertTrue(mayUploadBooks(signedIn, Power(5, true), manual = false))
        val caughtUp = signedIn.copy(booksCaughtUpOnce = true)
        assertFalse(mayUploadBooks(caughtUp, Power(70, false), manual = false))
        assertTrue(mayUploadBooks(caughtUp, Power(71, false), manual = false))
        assertTrue(mayUploadBooks(caughtUp, Power(5, true), manual = false))
    }

    @Test
    fun manualIgnoresChargingRule() {
        assertTrue(mayUploadBooks(signedIn, Power(1, false), manual = true))
    }

    @Test
    fun keepsFourteenNewest() {
        val kor = (1..16).map { "koreader-2026-10-%02d.zip".format(it) }
        val mng = (1..16).map { "manga-2026-10-%02d.tachibk".format(it) }
        val mixed = (kor + mng).shuffled(java.util.Random(7))
        val del = snapshotsToDelete(mixed)
        assertEquals(
            setOf(
                "koreader-2026-10-01.zip", "koreader-2026-10-02.zip",
                "manga-2026-10-01.tachibk", "manga-2026-10-02.tachibk",
            ),
            del.toSet(),
        )
        assertEquals(4, del.size)
    }

    @Test
    fun deletesOnlyWithinOneKindAndSkipsStrangers() {
        val names = (1..15).map { "koreader-2026-09-%02d.zip".format(it) } +
            listOf("notes.txt", "koreader-latest.zip", "manga-2026-10-01.tachibk")
        assertEquals(listOf("koreader-2026-09-01.zip"), snapshotsToDelete(names))
    }

    @Test
    fun failureMarkNeedsThreeDaysAndARealFailure() {
        val now = ms(today)
        val base = signedIn.copy(lastSuccessAt = now - 48 * hour, lastFailureAt = now - hour)
        assertFalse(showFailureMark(base, now))
        val threeDays = base.copy(lastSuccessAt = now - 72 * hour)
        assertTrue(showFailureMark(threeDays, now))
        assertFalse(showFailureMark(threeDays, now - 1))
        val skipsOnly = signedIn.copy(lastSuccessAt = now - 5 * 24 * hour)
        assertFalse(showFailureMark(skipsOnly, now))
        val healed = threeDays.copy(lastSuccessAt = now - 30 * 60_000L)
        assertFalse(showFailureMark(healed, now))
        assertFalse(showFailureMark(threeDays.copy(signedIn = false), now))
    }

    @Test
    fun failureMarkCountsFromSignInWhenNeverSucceeded() {
        val now = ms(today)
        val s = signedIn.copy(signedInAt = now - 73 * hour, lastFailureAt = now - hour)
        assertTrue(showFailureMark(s, now))
        assertFalse(showFailureMark(s.copy(signedInAt = now - 71 * hour), now))
        assertFalse(showFailureMark(s.copy(signedInAt = null), now))
    }
}
