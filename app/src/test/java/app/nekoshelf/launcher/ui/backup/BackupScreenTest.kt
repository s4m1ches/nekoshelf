package app.nekoshelf.launcher.ui.backup

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.nekoshelf.launcher.backup.BackupStatus
import app.nekoshelf.launcher.backup.RestoreItem
import app.nekoshelf.launcher.backup.RestorePlan
import app.nekoshelf.launcher.ui.home.HeaderContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupScreenTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 23)
    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 12, 0)

    private val signIns = mutableIntStateOf(0)
    private val backups = mutableIntStateOf(0)
    private val restores = mutableIntStateOf(0)
    private val declines = mutableIntStateOf(0)
    private val confirms = mutableIntStateOf(0)
    private val mangaSaves = mutableIntStateOf(0)
    private val inboxToggles = ArrayList<Boolean>()
    private val inboxChecks = mutableIntStateOf(0)

    private val signedIn = BackupStatus.EMPTY.copy(
        signedIn = true,
        account = "reader@example.com",
        signedInAt = at(today.minusDays(30), 10, 0),
        lastSuccessAt = at(today, 9, 14),
        koreaderSnapshots = 14,
        mangaSnapshots = 14,
        booksOnDrive = 212,
        booksLocal = 215,
        booksPending = 3,
    )

    private fun screen(status: BackupStatus, ui: BackupUi = BackupUi()) {
        rule.setContent {
            BackupScreen(
                status = status,
                onSignIn = { signIns.intValue++ },
                onBackupNow = { backups.intValue++ },
                onRestore = { restores.intValue++ },
                onDismiss = {},
                ui = ui,
                onDeclineOffer = { declines.intValue++ },
                onInboxToggle = { inboxToggles += it },
                onCheckInbox = { inboxChecks.intValue++ },
                nowMs = now,
                zone = zone,
            )
        }
    }

    private fun dialog(plan: RestorePlan?, manga: Boolean = true) {
        rule.setContent {
            RestoreDialog(
                plan = plan,
                mangaAvailable = manga,
                onConfirm = { confirms.intValue++ },
                onSaveManga = { mangaSaves.intValue++ },
                onDismiss = {},
            )
        }
    }

    @Test fun notSignedInShowsSignInButton() {
        screen(BackupStatus.EMPTY)
        rule.onNodeWithText("Бэкап в Google Диск").assertExists()
        rule.onNodeWithText("Закрыть").assertExists()
        rule.onNodeWithText("Войти в Google").performClick()
        rule.onNodeWithText("Сделать сейчас").assertDoesNotExist()
        rule.onNodeWithText("Восстановить…").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, signIns.intValue) }
    }

    @Test fun statusLinesMatchMockup() {
        screen(signedIn)
        for (line in listOf(
            "Аккаунт: reader@example.com",
            "Последний бэкап: сегодня 09:14 ✓",
            "Прогресс и статистика: 14 снимков",
            "Манга: 14 снимков",
            "Книги на Диске: 212 из 215 · 3 ждут зарядки",
            "Следующий: завтра при включении",
        )) rule.onNodeWithText(line).assertExists()
        rule.onNodeWithText("Войти в Google").assertDoesNotExist()
        rule.onNodeWithText("Сделать сейчас").assertIsEnabled().performClick()
        rule.onNodeWithText("Восстановить…").performClick()
        rule.runOnIdle {
            assertEquals(1, backups.intValue)
            assertEquals(1, restores.intValue)
        }
    }

    @Test fun failureShowsErrorText() {
        screen(
            signedIn.copy(
                lastSuccessAt = at(today.minusDays(1), 9, 14),
                lastFailureAt = at(today, 8, 5),
                lastError = "нет сети",
                koreaderSnapshots = 1,
                mangaSnapshots = 3,
                booksPending = 0,
                booksOnDrive = 215,
            ),
        )
        rule.onNodeWithText("Последний бэкап: сегодня 08:05 — нет сети").assertExists()
        rule.onNodeWithText("Последний удачный: вчера 09:14 ✓").assertExists()
        rule.onNodeWithText("Прогресс и статистика: 1 снимок").assertExists()
        rule.onNodeWithText("Манга: 3 снимка").assertExists()
        rule.onNodeWithText("Книги на Диске: 215 из 215").assertExists()
        rule.onNodeWithText("Следующий: при следующем включении").assertExists()
    }

    @Test fun revokedAccessOffersSignInAgain() {
        screen(signedIn.copy(lastFailureAt = at(today, 10, 0), lastError = "Google отозвал доступ — войдите заново"))
        rule.onNodeWithText("Последний бэкап: сегодня 10:00 — Google отозвал доступ — войдите заново").assertExists()
        rule.onNodeWithText("Войти в Google").performClick()
        rule.runOnIdle { assertEquals(1, signIns.intValue) }
    }

    @Test fun runningOrQueuedWorkDisablesBackupNow() {
        screen(signedIn, BackupUi(work = BackupWork.RUNNING))
        rule.onNodeWithText("Идёт бэкап…").assertExists()
        rule.onNodeWithText("Сделать сейчас").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(0, backups.intValue) }
    }

    @Test fun queuedWorkWaitsForNetwork() {
        screen(signedIn, BackupUi(work = BackupWork.ENQUEUED))
        rule.onNodeWithText("Ждёт сети…").assertExists()
        rule.onNodeWithText("Сделать сейчас").assertIsNotEnabled()
    }

    @Test fun pendingOfferAsksWithDateAndBlocksNextLine() {
        screen(signedIn.copy(restoreOfferPending = true), BackupUi(offerDate = LocalDate.of(2026, 10, 21)))
        rule.onNodeWithText("На Диске есть бэкап от 21 октября — восстановить?").assertExists()
        rule.onNodeWithText("Следующий: ждёт ответа про восстановление").assertExists()
        // A run now would make a fresh reader's near-empty snapshot the newest one.
        rule.onNodeWithText("Сделать сейчас").assertIsNotEnabled()
        rule.onNodeWithText("Не нужно").performClick()
        rule.runOnIdle { assertEquals(1, declines.intValue) }
    }

    @Test fun queuedOrRunningWorkDisablesBothRestoreButtons() {
        // A queued run starts as soon as the network is back — e.g. when the owner turns Wi-Fi on to restore.
        val work = mutableStateOf(BackupWork.ENQUEUED)
        rule.setContent {
            BackupScreen(
                status = signedIn.copy(restoreOfferPending = true), onSignIn = {}, onBackupNow = {},
                onRestore = { restores.intValue++ }, onDismiss = {}, ui = BackupUi(work = work.value),
                nowMs = now, zone = zone,
            )
        }
        for (w in listOf(BackupWork.ENQUEUED, BackupWork.RUNNING)) {
            work.value = w
            val buttons = rule.onAllNodesWithText("Восстановить…")
            buttons.assertCountEquals(2)
            for (i in 0 until 2) buttons[i].assertIsNotEnabled().performClick()
            rule.onNodeWithText("Не нужно").assertIsEnabled()
            rule.runOnIdle { assertEquals(0, restores.intValue) }
        }
    }

    @Test fun offerCardIsHiddenWhileARestoreHoldsBackups() {
        screen(signedIn.copy(restoreOfferPending = true), BackupUi(offerDate = LocalDate.of(2026, 10, 21), restoring = true))
        rule.onNodeWithText("На Диске есть бэкап от 21 октября — восстановить?").assertDoesNotExist()
        rule.onNodeWithText("Не нужно").assertDoesNotExist()
    }

    @Test fun busyDialogDisablesRestoreAndMangaSaveButNotCancel() {
        val items = listOf(RestoreItem("books/x.sdr/m.lua", null, File("x")))
        rule.setContent {
            RestoreDialog(
                plan = RestorePlan(items, books = 0, sdr = 1, statsRestored = false),
                mangaAvailable = true,
                onConfirm = { confirms.intValue++ },
                onSaveManga = { mangaSaves.intValue++ },
                onDismiss = {},
                message = "Сохраняю…",
                busy = true,
            )
        }
        rule.onNodeWithText("Сохраняю…").assertExists()
        rule.onNodeWithText("Восстановить").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Скачать бэкап Манги в Загрузки").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Отмена").assertIsEnabled()
        rule.runOnIdle {
            assertEquals(0, confirms.intValue)
            assertEquals(0, mangaSaves.intValue)
        }
    }

    @Test fun messageIsShown() {
        screen(signedIn, BackupUi(message = "Восстановлено, но не удалось: 2"))
        rule.onNodeWithText("Восстановлено, но не удалось: 2").assertExists()
    }

    @Test fun emptyPlanOffersOnlyClose() {
        dialog(RestorePlan(emptyList(), books = 0, sdr = 0, statsRestored = false))
        rule.onNodeWithText("На читалке всё на месте — восстанавливать нечего.").assertExists()
        rule.onNodeWithText("Закрыть").assertExists()
        rule.onNodeWithText("Восстановить").assertDoesNotExist()
        rule.onNodeWithText("Отмена").assertDoesNotExist()
        rule.onNodeWithText("Восстановить?").assertDoesNotExist()
        rule.onNodeWithText("Скачать бэкап Манги в Загрузки").performClick()
        rule.runOnIdle { assertEquals(1, mangaSaves.intValue) }
    }

    @Test fun planSummaryText() {
        val items = List(5) { RestoreItem("books/x$it.sdr/m.lua", null, File("x$it")) }
        dialog(RestorePlan(items, books = 3, sdr = 2, statsRestored = false))
        rule.onNodeWithText("Вернётся: книг 3, прогресс 2 книг, статистика — нет (на читалке есть своя).").assertExists()
        rule.onNodeWithText("Восстановить?").assertExists()
        rule.onNodeWithText("Перед восстановлением закрой KOReader.").assertExists()
        rule.onNodeWithText("Отмена").assertExists()
        rule.onNodeWithText("Восстановить").performClick()
        rule.runOnIdle { assertEquals(1, confirms.intValue) }
    }

    @Test fun progressCountUsesTheGenitive() {
        val items = listOf(RestoreItem("books/x.sdr/m.lua", null, File("x")))
        val sdr = mutableIntStateOf(1)
        rule.setContent {
            RestoreDialog(RestorePlan(items, books = 1, sdr = sdr.intValue, statsRestored = false), false, {}, {}, {})
        }
        for ((n, words) in listOf(1 to "1 книги", 2 to "2 книг", 5 to "5 книг", 11 to "11 книг", 21 to "21 книги")) {
            sdr.intValue = n
            rule.onNodeWithText("Вернётся: книг 1, прогресс $words, статистика — нет (на читалке есть своя).").assertExists()
        }
    }

    @Test fun planWithStatsSaysYes() {
        val items = listOf(RestoreItem("koreader/settings/statistics.sqlite3", null, File("s")))
        dialog(RestorePlan(items, books = 0, sdr = 0, statsRestored = true), manga = false)
        rule.onNodeWithText("Вернётся: книг 0, прогресс 0 книг, статистика — да.").assertExists()
        rule.onNodeWithText("Скачать бэкап Манги в Загрузки").assertDoesNotExist()
    }

    @Test fun computingShowsCheckingText() {
        dialog(plan = null)
        rule.onNodeWithText("Проверяю, чего не хватает…").assertExists()
        rule.onNodeWithText("Восстановить").assertDoesNotExist()
    }

    @Test fun headerShowsTheFailureMarkLeftOfTheBattery() {
        val failing = androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            HeaderContent(
                picture = null, decors = emptyList(), time = "09:14", date = "пятница, 23 октября",
                wifiOn = false, lightOn = false, battery = 80,
                onWifi = {}, onWifiLong = {}, onLight = {}, onLightLong = {}, onRefresh = {},
                backupFailing = failing.value,
            )
        }
        rule.onNodeWithText("80%").assertExists()
        rule.onNodeWithText("☁︎!").assertDoesNotExist()
        failing.value = true
        val mark = rule.onNodeWithText("☁︎!").assertExists().fetchSemanticsNode().boundsInRoot
        val battery = rule.onNodeWithText("80%").fetchSemanticsNode().boundsInRoot
        assertTrue("mark $mark must be left of battery $battery", mark.right <= battery.left)
    }

    // ---- «Забирать новые книги с Диска» ----------------------------------------------------------

    private val inboxOn = signedIn.copy(inboxEnabled = true)

    @Test fun inboxOffShowsAnUncheckedBoxAndNoStatusLine() {
        screen(signedIn)
        rule.onNodeWithText("Забирать новые книги с Диска").assertIsOff().performClick()
        rule.onNodeWithText("Новые книги с Диска", substring = true).assertDoesNotExist()
        rule.onNodeWithText("нет доступа к Диску — включите заново").assertDoesNotExist()
        rule.onNodeWithText("Проверить новые книги").assertIsNotEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf(true), inboxToggles)
            assertEquals(0, inboxChecks.intValue)
        }
    }

    @Test fun inboxOnBeforeTheFirstCheck() {
        screen(inboxOn)
        rule.onNodeWithText("Забирать новые книги с Диска").assertIsOn()
        rule.onNodeWithText("Новые книги с Диска: ещё не проверялось").assertExists()
        rule.onNodeWithText("Проверить новые книги").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, inboxChecks.intValue) }
    }

    @Test fun uncheckingTurnsTheInboxOff() {
        screen(inboxOn)
        rule.onNodeWithText("Забирать новые книги с Диска").performClick()
        rule.runOnIdle { assertEquals(listOf(false), inboxToggles) }
    }

    @Test fun inboxStatusLineShowsTheLastCheckAndTheCount() {
        val status = mutableStateOf(inboxOn.copy(inboxLastCheckAt = at(today, 8, 12), inboxReceived = 2))
        rule.setContent {
            BackupScreen(status = status.value, onSignIn = {}, onBackupNow = {}, onRestore = {}, onDismiss = {}, nowMs = now, zone = zone)
        }
        rule.onNodeWithText("Новые книги с Диска: последняя проверка сегодня 08:12, получено 2").assertExists()
        status.value = inboxOn.copy(inboxLastCheckAt = at(today.minusDays(1), 23, 0), inboxReceived = 0)
        rule.onNodeWithText("Новые книги с Диска: последняя проверка вчера 23:00, получено 0").assertExists()
        status.value = inboxOn.copy(inboxLastCheckAt = at(LocalDate.of(2026, 10, 1), 7, 5), inboxReceived = 1)
        rule.onNodeWithText("Новые книги с Диска: последняя проверка 1 октября 07:05, получено 1").assertExists()
    }

    @Test fun inboxErrorReplacesTheCount() {
        screen(inboxOn.copy(inboxLastCheckAt = at(today, 8, 12), inboxReceived = 3, inboxError = "мало места"))
        rule.onNodeWithText("Новые книги с Диска: последняя проверка сегодня 08:12, мало места").assertExists()
    }

    @Test fun needsReauthShowsTheErrorAndATapTurnsItOnAgain() {
        screen(inboxOn.copy(inboxNeedsReauth = true, inboxLastCheckAt = at(today, 8, 12), inboxError = "нет доступа к Диску — включите заново"))
        rule.onNodeWithText("нет доступа к Диску — включите заново").assertExists()
        rule.onNodeWithText("Забирать новые книги с Диска").assertIsOff().performClick()
        rule.onNodeWithText("Проверить новые книги").assertIsNotEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf(true), inboxToggles)
            assertEquals(0, inboxChecks.intValue)
        }
    }

    @Test fun aFailedTurnOnShowsTheErrorUnderTheBox() {
        screen(signedIn, BackupUi(inboxMessage = "нет доступа к Диску — включите заново"))
        rule.onNodeWithText("нет доступа к Диску — включите заново").assertExists()
        rule.onNodeWithText("Забирать новые книги с Диска").assertIsOff()
    }

    @Test fun inboxWorkQueuedOrRunningDisablesTheCheckButton() {
        val work = mutableStateOf(BackupWork.ENQUEUED)
        rule.setContent {
            BackupScreen(
                status = inboxOn, onSignIn = {}, onBackupNow = { backups.intValue++ }, onRestore = {}, onDismiss = {},
                ui = BackupUi(inboxWork = work.value), onCheckInbox = { inboxChecks.intValue++ }, nowMs = now, zone = zone,
            )
        }
        for (w in listOf(BackupWork.ENQUEUED, BackupWork.RUNNING)) {
            work.value = w
            rule.onNodeWithText("Проверить новые книги").assertIsNotEnabled().performClick()
            // The backup's own button does not wait for the inbox.
            rule.onNodeWithText("Сделать сейчас").assertIsEnabled()
        }
        work.value = BackupWork.IDLE
        rule.onNodeWithText("Проверить новые книги").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, inboxChecks.intValue) }
    }

    @Test fun busyDisablesTheCheckbox() {
        screen(signedIn, BackupUi(busy = true))
        rule.onNodeWithText("Забирать новые книги с Диска").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertTrue(inboxToggles.isEmpty()) }
    }

    @Test fun backupTimesAreTodayYesterdayOrDate() {
        assertEquals("сегодня 09:14", backupTimeText(at(today, 9, 14), now, zone))
        assertEquals("вчера 23:59", backupTimeText(at(today.minusDays(1), 23, 59), now, zone))
        assertEquals("1 октября 07:00", backupTimeText(at(LocalDate.of(2026, 10, 1), 7, 0), now, zone))
    }

    @Test fun russianPlurals() {
        assertEquals(
            listOf("1 снимок", "2 снимка", "5 снимков", "11 снимков", "21 снимок", "22 снимка", "0 снимков"),
            listOf(1, 2, 5, 11, 21, 22, 0).map { "$it " + ruPlural(it, "снимок", "снимка", "снимков") },
        )
    }
}
