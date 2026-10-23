package app.meebook.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.apps.AppEntry
import app.meebook.launcher.data.HomeData
import app.meebook.launcher.device.Toggles
import app.meebook.launcher.domain.Book
import app.meebook.launcher.domain.DecorSpot
import app.meebook.launcher.domain.Manga
import app.meebook.launcher.domain.ShelfBuilder
import app.meebook.launcher.domain.ShelfTab
import app.meebook.launcher.ui.AboutDialog
import app.meebook.launcher.ui.apps.AllAppsScreen
import app.meebook.launcher.ui.stats.StatsScreen
import java.io.File

/** What covers the home screen, if anything. */
sealed interface Overlay {
    data object AllApps : Overlay
    data object Stats : Overlay
    data object About : Overlay
    data class DockPick(val index: Int) : Overlay
}

/** Observable home-screen state, owned by the activity (which refreshes it in onStart). */
@Stable
class HomeState(initialTab: ShelfTab) {
    var data by mutableStateOf(HomeData.empty())
    var picture by mutableStateOf<File?>(null)
    var decors by mutableStateOf<List<DecorSpot>>(emptyList())
    var dock by mutableStateOf<List<AppEntry?>>(emptyList())
    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
    var needsPermission by mutableStateOf(false)
    var tab by mutableStateOf(initialTab)
    var overlay by mutableStateOf<Overlay?>(null)
    val nav = ShelfNav()
    val appsNav = ShelfNav()
    /** Pages the stats screen's years (page 0 = the oldest year). */
    val statsNav = ShelfNav()
}

/** Everything the home screen asks the activity to do. */
interface HomeActions {
    fun openBook(book: Book)
    fun openManga(manga: Manga)
    fun openApp(app: AppEntry)
    fun appDetails(app: AppEntry)
    fun selectTab(tab: ShelfTab)
    fun fullRefresh()
    fun pictureSettled()
    fun requestStorage()
    fun showAllApps()
    fun showStats()
    fun showAbout()
    fun pickDock(index: Int)
    fun setDock(index: Int, app: AppEntry)
    fun closeOverlay()
    /** The header clock ticked (once a minute while started). */
    fun clockTick()
}

/**
 * The home screen, top to bottom: header, (storage prompt), stats, «Сейчас читаю», shelf tabs,
 * the paged shelf, the dock. Swipe up anywhere opens all apps. No animations anywhere.
 */
@Composable
fun HomeScreen(state: HomeState, toggles: Toggles, actions: HomeActions) {
    val data = state.data
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize().swipes(onUp = actions::showAllApps)) {
            Header(
                picture = state.picture,
                decors = state.decors,
                toggles = toggles,
                onRefresh = actions::fullRefresh,
                onPictureSettled = actions::pictureSettled,
                onAbout = actions::showAbout,
                onTick = actions::clockTick,
            )
            if (state.needsPermission) {
                PermissionCard(actions::requestStorage, Modifier.padding(top = 6.dp))
            }
            StatsBlock(
                books = data.bookStats,
                manga = if (data.mangaAvailable) data.mangaStats else null,
                mangaInstalled = data.mangaInstalled,
                mangaLoaded = data.mangaLoaded,
                week = data.week,
                today = data.today,
                loaded = data.loaded,
                onOpenStats = actions::showStats,
                modifier = Modifier.padding(top = 4.dp),
            )
            NowReading(
                book = data.nowBook,
                bookCover = data.nowBook?.let { data.bookCovers[it.file] },
                manga = data.nowManga,
                mangaCover = data.nowManga?.let { data.mangaCovers[it.id] },
                mangaAvailable = data.mangaAvailable,
                mangaInstalled = data.mangaInstalled,
                mangaLoaded = data.mangaLoaded,
                loaded = data.loaded,
                onBook = actions::openBook,
                onManga = actions::openManga,
                modifier = Modifier.padding(top = 4.dp),
            )
            ShelfTabs(state.tab, actions::selectTab, Modifier.padding(top = 8.dp))
            val items = remember(data, state.tab) { ShelfBuilder.build(data.books, data.manga, state.tab, data.nowMs) }
            Shelf(
                items = items,
                bookCovers = data.bookCovers,
                mangaCovers = data.mangaCovers,
                nav = state.nav,
                emptyText = when {
                    !data.loaded -> ""
                    state.tab == ShelfTab.MANGA && !data.mangaLoaded -> ""
                    state.tab == ShelfTab.MANGA && !data.mangaAvailable -> stringResource(mangaUnavailableText(data.mangaInstalled))
                    else -> null
                },
                onBook = actions::openBook,
                onManga = actions::openManga,
                modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            Dock(state.dock, actions::openApp, actions::pickDock)
        }
        when (val o = state.overlay) {
            null -> Unit
            Overlay.AllApps -> AllAppsScreen(
                apps = state.allApps,
                nav = state.appsNav,
                onOpen = actions::openApp,
                onDetails = actions::appDetails,
                onDismiss = actions::closeOverlay,
            )
            Overlay.Stats -> StatsScreen(
                data = data,
                nav = state.statsNav,
                onDismiss = actions::closeOverlay,
            )
            Overlay.About -> AboutDialog(onDismiss = actions::closeOverlay)
            is Overlay.DockPick -> AppPicker(
                title = stringResource(R.string.dock_pick),
                apps = state.allApps,
                onPick = { actions.setDock(o.index, it) },
                onDismiss = actions::closeOverlay,
            )
        }
    }
}

/** Ruling R6: one line + a button; the button re-requests (or opens app settings if denied for good). */
@Composable
private fun PermissionCard(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding).outlinedCard().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.perm_needed), style = serif(13f, bold = true),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.padding(start = 8.dp).background(Color.Black, CardShape)
                .clickable(role = Role.Button, onClick = onGrant).padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.perm_button), style = serif(14f, bold = true, color = Color.White))
        }
    }
}
