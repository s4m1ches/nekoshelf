package app.meebook.launcher.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Power(val percent: Int, val charging: Boolean)

private const val BATTERY_OK_ABOVE = 70
private const val FAILURE_MARK_AFTER_MS = 72L * 60 * 60 * 1000
private val SNAPSHOT_DATE = Regex("""^(?:koreader-(\d{4}-\d{2}-\d{2})\.zip|manga-(\d{4}-\d{2}-\d{2})\.tachibk)$""")

private fun Power.enough() = charging || percent > BATTERY_OK_ABOVE

fun shouldRunAuto(status: BackupStatus, today: LocalDate, power: Power, zone: ZoneId): Boolean {
    if (!status.signedIn || status.restoreOfferPending) return false
    val last = status.lastSuccessAt
    if (last != null && Instant.ofEpochMilli(last).atZone(zone).toLocalDate() == today) return false
    return power.enough()
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
