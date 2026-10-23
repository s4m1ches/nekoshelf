package app.meebook.launcher.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.backup.RestorePlan
import app.meebook.launcher.ui.home.outlinedCard
import app.meebook.launcher.ui.home.serif

/** The restore dialog's state: [plan] null while it is being computed; [message] = the «Манга» save's result. */
data class RestoreUi(val plan: RestorePlan?, val mangaAvailable: Boolean, val message: String? = null)

/**
 * «Восстановить…»: first what would come back, then one question. Drawn in place over the backup
 * screen (a card; touches outside it go nowhere), no animation:
 * - [plan] null: «Проверяю, чего не хватает…»;
 * - nothing missing: says so, only «Закрыть»;
 * - otherwise the summary, «Восстановить?», the KOReader warning, «Восстановить» / «Отмена».
 * Once the plan is known, «Скачать бэкап Манги в Загрузки» is offered when Drive has one
 * ([mangaAvailable]). Nothing is written before [onConfirm]. Back closes it.
 */
@Composable
fun RestoreDialog(
    plan: RestorePlan?,
    mangaAvailable: Boolean,
    onConfirm: () -> Unit,
    onSaveManga: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    BackHandler(onBack = onDismiss)
    // Swallows touches around the card, so nothing under the dialog reacts.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).fillMaxWidth().outlinedCard().padding(16.dp)) {
            when {
                plan == null -> {
                    Text(stringResource(R.string.restore_checking), style = serif(15f, bold = true))
                    Row(Modifier.padding(top = 14.dp)) {
                        OutlinedButton(stringResource(R.string.restore_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                    }
                }
                plan.items.isEmpty() -> {
                    Text(stringResource(R.string.restore_nothing), style = serif(15f, bold = true))
                    MangaSave(mangaAvailable, onSaveManga, message)
                    Row(Modifier.padding(top = 14.dp)) {
                        OutlinedButton(stringResource(R.string.close), onClick = onDismiss, modifier = Modifier.weight(1f))
                    }
                }
                else -> {
                    val stats = stringResource(if (plan.statsRestored) R.string.restore_stats_yes else R.string.restore_stats_no)
                    Text(stringResource(R.string.restore_summary, plan.books, plan.sdr, stats), style = serif(15f))
                    Text(stringResource(R.string.restore_ask), style = serif(15f, bold = true), modifier = Modifier.padding(top = 6.dp))
                    Text(stringResource(R.string.restore_close_koreader), style = serif(13f), modifier = Modifier.padding(top = 6.dp))
                    MangaSave(mangaAvailable, onSaveManga, message)
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledButton(stringResource(R.string.restore_confirm), onClick = onConfirm, modifier = Modifier.weight(1f))
                        OutlinedButton(stringResource(R.string.restore_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaSave(available: Boolean, onSave: () -> Unit, message: String?) {
    if (available) {
        Row(Modifier.padding(top = 12.dp)) {
            OutlinedButton(stringResource(R.string.restore_save_manga), onClick = onSave, modifier = Modifier.weight(1f))
        }
    }
    if (message != null) Text(message, style = serif(13f, bold = true), modifier = Modifier.padding(top = 6.dp))
}
