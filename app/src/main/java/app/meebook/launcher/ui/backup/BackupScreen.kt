package app.meebook.launcher.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.backup.BACKUP_ERROR_AUTH
import app.meebook.launcher.backup.BackupStatus
import app.meebook.launcher.ui.home.CardBorder
import app.meebook.launcher.ui.home.CardShape
import app.meebook.launcher.ui.home.Grey
import app.meebook.launcher.ui.home.ScreenPadding
import app.meebook.launcher.ui.home.outlinedCard
import app.meebook.launcher.ui.home.serif
import app.meebook.launcher.ui.home.swipes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The unique backup work as WorkManager reports it (ruling R22). */
enum class BackupWork { IDLE, ENQUEUED, RUNNING }

/**
 * What the backup screen shows beside the stored [BackupStatus]: the work's state, the result of
 * the last action ([message]), the date for the restore offer, [busy] while a sign-in,
 * restore or «Манга» save is under way (its buttons are then disabled), and [restoring] while a
 * restore holds backups (from «Восстановить…» until it ends; the offer card is hidden then).
 */
data class BackupUi(
    val work: BackupWork = BackupWork.IDLE,
    val message: String? = null,
    val offerDate: LocalDate? = null,
    val busy: Boolean = false,
    val restoring: Boolean = false,
)

/** What the backup screen and the restore dialog ask the activity to do. */
interface BackupActions {
    fun signIn()
    fun backupNow()
    /** «Восстановить…» (the button or the offer): computes the plan and opens [RestoreDialog]. */
    fun openRestore()
    /** «Не нужно» on the restore offer. */
    fun declineRestoreOffer()
    fun confirmRestore()
    fun saveMangaBackup()
    fun closeRestore()
}

private val RU = Locale.forLanguageTag("ru")
private val HM = DateTimeFormatter.ofPattern("HH:mm", RU)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RU)
private val DisabledGrey = Color(0xFFAAAAAA)

/** «сегодня 09:14» / «вчера 09:14» / «23 октября 09:14». */
internal fun backupTimeText(ms: Long, nowMs: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val hm = HM.format(t)
    return when (t.toLocalDate()) {
        today -> "сегодня $hm"
        today.minusDays(1) -> "вчера $hm"
        else -> "${DAY_MONTH.format(t)} $hm"
    }
}

/** «23 октября». */
internal fun dayMonthText(day: LocalDate): String = DAY_MONTH.format(day)

/** The Russian plural form of a word for [n]: 1 снимок, 2 снимка, 5 снимков, 21 снимок. */
internal fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}

/**
 * «Бэкап в Google Диск» (from the «☁ Бэкап» button in all apps), as in the approved mockup, top to
 * bottom: the account (or «Войти в Google»), the restore offer while it is pending, the last
 * backup (or what failed and when), snapshot counts, books on Drive, the next run, the work's
 * state, «Сделать сейчас» / «Восстановить…», and the last action's [BackupUi.message]. Text only —
 * no spinner, no animation. Back, «Закрыть» or a swipe down closes it.
 */
@Composable
fun BackupScreen(
    status: BackupStatus,
    onSignIn: () -> Unit,
    onBackupNow: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    ui: BackupUi = BackupUi(),
    onDeclineOffer: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onDismiss)
    Column(Modifier.fillMaxSize().background(Color.White).padding(ScreenPadding).swipes(onDown = onDismiss)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.backup_title), style = serif(20f, bold = true),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(stringResource(R.string.close), onClick = onDismiss)
        }

        if (!status.signedIn) {
            FilledButton(stringResource(R.string.backup_sign_in), enabled = !ui.busy, onClick = onSignIn, modifier = Modifier.padding(top = 12.dp))
            Message(ui.message)
            return@Column
        }

        val failureNewer = status.lastError != null && status.lastFailureAt != null &&
            status.lastFailureAt > (status.lastSuccessAt ?: Long.MIN_VALUE)
        val authLost = failureNewer && status.lastError == BACKUP_ERROR_AUTH

        Line(stringResource(R.string.backup_account, status.account ?: stringResource(R.string.backup_account_unknown)))
        if (authLost) {
            FilledButton(stringResource(R.string.backup_sign_in), enabled = !ui.busy, onClick = onSignIn, modifier = Modifier.padding(vertical = 6.dp))
        }

        if (status.restoreOfferPending && !ui.restoring) {
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().outlinedCard().padding(12.dp)) {
                val offer = ui.offerDate?.let { stringResource(R.string.backup_offer, dayMonthText(it)) }
                    ?: stringResource(R.string.backup_offer_no_date)
                Text(offer, style = serif(15f, bold = true))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(stringResource(R.string.backup_restore), enabled = !ui.busy, onClick = onRestore, modifier = Modifier.weight(1f))
                    OutlinedButton(stringResource(R.string.backup_offer_no), enabled = !ui.busy, onClick = onDeclineOffer, modifier = Modifier.weight(1f))
                }
            }
        }

        val success = status.lastSuccessAt
        when {
            failureNewer -> {
                Line(stringResource(R.string.backup_last_failed, backupTimeText(status.lastFailureAt, nowMs, zone), status.lastError))
                if (success != null) Line(stringResource(R.string.backup_last_ok, backupTimeText(success, nowMs, zone)))
            }
            success != null -> Line(stringResource(R.string.backup_last, backupTimeText(success, nowMs, zone)))
            else -> Line(stringResource(R.string.backup_last_none))
        }
        Line(stringResource(R.string.backup_koreader, snapshots(status.koreaderSnapshots)))
        Line(stringResource(R.string.backup_manga, snapshots(status.mangaSnapshots)))
        Line(
            when {
                status.booksPending <= 0 -> stringResource(R.string.backup_books, status.booksOnDrive, status.booksLocal)
                !status.booksCaughtUpOnce -> stringResource(R.string.backup_books_charger, status.booksOnDrive, status.booksLocal, status.booksPending)
                else -> stringResource(R.string.backup_books_waiting, status.booksOnDrive, status.booksLocal, status.booksPending)
            },
        )
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val next = when {
            status.restoreOfferPending -> R.string.backup_next_offer
            authLost -> R.string.backup_next_sign_in
            success != null && Instant.ofEpochMilli(success).atZone(zone).toLocalDate() == today -> R.string.backup_next_tomorrow
            else -> R.string.backup_next_wake
        }
        Line(stringResource(R.string.backup_next, stringResource(next)))

        when (ui.work) {
            BackupWork.RUNNING -> Text(stringResource(R.string.backup_running), style = serif(15f, bold = true), modifier = Modifier.padding(top = 6.dp))
            BackupWork.ENQUEUED -> Text(stringResource(R.string.backup_waiting_network), style = serif(15f, bold = true), modifier = Modifier.padding(top = 6.dp))
            BackupWork.IDLE -> Unit
        }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Disabled while a run is queued or running: presses would only chain more runs (R22);
            // and while the restore offer waits: a run would make a near-empty snapshot the newest.
            FilledButton(
                stringResource(R.string.backup_now),
                enabled = ui.work == BackupWork.IDLE && !ui.busy && !status.restoreOfferPending,
                onClick = onBackupNow,
                modifier = Modifier.weight(1f),
            )
            // Not while a run reads the very files a restore would write.
            OutlinedButton(
                stringResource(R.string.backup_restore),
                enabled = ui.work != BackupWork.RUNNING && !ui.busy,
                onClick = onRestore,
                modifier = Modifier.weight(1f),
            )
        }
        Message(ui.message)
    }
}

@Composable
private fun snapshots(n: Int): String =
    "$n " + ruPlural(n, stringResource(R.string.snapshot_one), stringResource(R.string.snapshot_few), stringResource(R.string.snapshot_many))

/** «Label: value» with the label in grey, as in the mockup; one text node. */
@Composable
private fun Line(text: String) {
    val cut = text.indexOf(": ")
    val styled = buildAnnotatedString {
        if (cut > 0) {
            withStyle(SpanStyle(color = Grey)) { append(text.substring(0, cut + 2)) }
            append(text.substring(cut + 2))
        } else {
            append(text)
        }
    }
    Text(styled, style = serif(15f), modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Message(text: String?) {
    if (text != null) Text(text, style = serif(15f, bold = true), modifier = Modifier.padding(top = 12.dp))
}

/** Black, white bold text («Сделать сейчас»); grey when disabled. */
@Composable
internal fun FilledButton(text: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CardShape).background(if (enabled) Color.Black else DisabledGrey)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = serif(15f, bold = true, color = Color.White), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Outlined card with bold text («Восстановить…», «Закрыть»); grey when disabled. */
@Composable
internal fun OutlinedButton(text: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (enabled) Color.Black else DisabledGrey
    Box(
        modifier.clip(CardShape).background(Color.White).border(CardBorder, color, CardShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = serif(15f, bold = true, color = color), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

