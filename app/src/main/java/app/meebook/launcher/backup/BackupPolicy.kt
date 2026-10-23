package app.meebook.launcher.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Power(val percent: Int, val charging: Boolean)

private const val BATTERY_OK_ABOVE = 70
private const val FAILURE_MARK_AFTER_MS = 72L * 60 * 60 * 1000
private val SNAPSHOT_DATE = Regex("""^(?:koreader-(\d{4}-\d{2}-\d{2})\.zip|manga-(\d{4}-\d{2}-\d{2})\.tachibk)$""")

private fun Power.enough() = charging || percent > BATTERY_OK_ABOVE

/** Ruling R32: after this many failed automatic runs on a day, automatic runs wait for the next day. */
internal const val MAX_FAILED_AUTO_PER_DAY = 3

/**
 * Whether a wake should queue an automatic run: signed in, no restore offer/restore holding
 * backups, fewer than [MAX_FAILED_AUTO_PER_DAY] failed automatic runs today, and either no
 * success today and battery > 70 % or charging — or (R31) books still waiting for the first full
 * sync while charging, even after today's success.
 */
fun shouldRunAuto(status: BackupStatus, today: LocalDate, power: Power, zone: ZoneId): Boolean {
    if (!status.signedIn || status.restoreOfferPending) return false
    if (status.failedAutoDay == today.toString() && status.failedAutoAttempts >= MAX_FAILED_AUTO_PER_DAY) return false
    val last = status.lastSuccessAt
    if (last != null && Instant.ofEpochMilli(last).atZone(zone).toLocalDate() == today) {
        return !status.booksCaughtUpOnce && status.booksPending > 0 && power.charging
    }
    return power.enough()
}

/** One more failed automatic run on [today] (R32). The caller leaves network-class failures out. */
fun BackupStatus.countFailedAuto(today: LocalDate): BackupStatus {
    val day = today.toString()
    return copy(failedAutoDay = day, failedAutoAttempts = if (failedAutoDay == day) failedAutoAttempts + 1 else 1)
}

fun mayUploadBooks(status: BackupStatus, power: Power, manual: Boolean): Boolean = when {
    manual -> true
    !status.booksCaughtUpOnce -> power.charging
    else -> power.enough()
}

/** Names to delete: per kind (koreader/manga), everything but the [keep] newest by date in the name. */
fun snapshotsToDelete(names: List<String>, keep: Int = 14): List<String> =
    names.mapNotNull { name ->
        val m = SNAPSHOT_DATE.matchEntire(name) ?: return@mapNotNull null
        val kind = if (m.groups[1] != null) "koreader" else "manga"
        Triple(kind, m.groups[1]?.value ?: m.groups[2]!!.value, name)
    }
        .groupBy { it.first }
        .values
        .flatMap { group -> group.sortedByDescending { it.second }.drop(keep) }
        .map { it.third }

fun showFailureMark(status: BackupStatus, nowMs: Long): Boolean {
    if (!status.signedIn) return false
    val failure = status.lastFailureAt ?: return false
    if (failure <= (status.lastSuccessAt ?: 0L)) return false
    val since = status.lastSuccessAt ?: status.signedInAt ?: nowMs
    return nowMs - since >= FAILURE_MARK_AFTER_MS
}
