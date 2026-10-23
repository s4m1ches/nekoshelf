package app.meebook.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.meebook.launcher.apps.AppEntry
import app.meebook.launcher.apps.AppsRepository
import app.meebook.launcher.data.HomeData
import app.meebook.launcher.data.HomeLoader
import app.meebook.launcher.device.Eink
import app.meebook.launcher.device.HeaderPictures
import app.meebook.launcher.device.Launch
import app.meebook.launcher.device.StorageAccess
import app.meebook.launcher.device.Toggles
import app.meebook.launcher.domain.Book
import app.meebook.launcher.domain.Manga
import app.meebook.launcher.domain.ShelfTab
import app.meebook.launcher.ui.home.HomeActions
import app.meebook.launcher.ui.home.HomeScreen
import app.meebook.launcher.ui.home.HomeState
import app.meebook.launcher.ui.home.Overlay
import app.meebook.launcher.ui.home.catgirlDecors
import app.meebook.launcher.ui.theme.EinkTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * The HOME activity. Every onStart (each return to the home screen) it picks the next header
 * picture and decor, reloads books / manga / stats on Dispatchers.IO and, once that content is
 * on screen, does exactly one e-ink full refresh. While started it listens to «Манга» changes.
 * Hardware page keys page the shelf (or the app grid while all apps is open).
 */
class MainActivity : ComponentActivity(), HomeActions {
    private lateinit var prefs: SharedPreferences
    private lateinit var toggles: Toggles
    private lateinit var loader: HomeLoader
    private lateinit var apps: AppsRepository
    private lateinit var pictures: HeaderPictures
    private lateinit var state: HomeState

    private var observer: ContentObserver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var startJob: Job? = null
    private var quietJob: Job? = null
    private var pictureSettled: CompletableDeferred<Unit>? = null
    /** System.nanoTime() of the running refresh's start, for the timing log. */
    private var refreshStart = 0L
    /** The storage dialog is shown automatically at most once per process (then via the card). */
    private var autoAsked = false

    private val storageRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean(PREF_ASKED, true).apply()
        state.needsPermission = !hasStorage()
        // Reload what storage gates (books, stats, picture if none yet); one full refresh clears
        // the dialog's ghost.
        if (!state.needsPermission) refresh(newPicture = state.picture == null, fullRefresh = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        Log.i(TAG, "page keys: HAOQING_PAGE_UP=$HAOQING_PAGE_UP HAOQING_PAGE_DOWN=$HAOQING_PAGE_DOWN")
        toggles = Toggles(this)
        loader = HomeLoader(this)
        apps = AppsRepository(this, prefs)
        pictures = HeaderPictures(File(Environment.getExternalStorageDirectory(), "Pictures"), prefs)
        val tab = runCatching { ShelfTab.valueOf(prefs.getString(PREF_TAB, null) ?: "") }.getOrDefault(ShelfTab.ALL)
        state = HomeState(tab)
        setContent {
            EinkTheme { HomeScreen(state, toggles, this) }
        }
    }

    override fun onStart() {
        super.onStart()
        state.overlay = null
        state.decor = nextDecor()
        observer = loader.bridge.registerObserver { quietRefresh() }
        state.needsPermission = !hasStorage()
        if (state.needsPermission && !autoAsked) {
            autoAsked = true
            askStorage(fromCard = false)
        }
        registerPackageReceiver()
        refresh(newPicture = true, fullRefresh = true)
    }

    override fun onStop() {
        packageReceiver?.let { runCatching { unregisterReceiver(it) } }
        packageReceiver = null
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        super.onStop()
    }

    /**
     * HOME pressed while already home (still resumed): back to the first shelf page, overlays
     * closed. A HOME press from another app also lands here, but before onResume — then the
     * shelf keeps its page.
     */
    override fun onNewIntent(intent: Intent) {
        val alreadyHome = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        super.onNewIntent(intent)
        state.overlay = null
        if (alreadyHome) state.nav.page = 0
    }

    // ---- refresh -----------------------------------------------------------------------------

    private fun refresh(newPicture: Boolean, fullRefresh: Boolean) {
        startJob?.cancel()
        quietJob?.cancel()
        startJob = lifecycleScope.launch {
            val t0 = System.nanoTime()
            refreshStart = t0
            val oldPicture = state.picture
            val settled = CompletableDeferred<Unit>()
            pictureSettled = settled
            // Data and picture in parallel: the load starts at once on IO, without waiting for the
            // picture pick or for the main thread (busy with the first frame on a cold start), and
            // runs while Coil decodes the picture (≈2 s for a big JPEG on a cold start). The load
            // hands over the books phase first, so a slow «Манга» never holds the books back.
            val booksPhase = CompletableDeferred<HomeData>()
            val load = async(Dispatchers.IO) {
                try {
                    loader.load(onBooks = { booksPhase.complete(it) })
                } catch (e: Throwable) {
                    booksPhase.completeExceptionally(e)
                    throw e
                }
            }
            val dock = async(Dispatchers.IO) { apps.dockSlots() }
            val picture = if (newPicture) withContext(Dispatchers.IO) { pictures.next() } else oldPicture
            state.picture = picture
            state.data = booksPhase.await()
            state.dock = dock.await()
            val booksAt = System.nanoTime()
            Log.i(TAG, "books in ${(booksAt - t0) / 1_000_000} ms")
            if (!fullRefresh) {
                state.data = load.await()
                return@launch
            }
            // Wait until the new picture is drawn (or failed), then for that frame to be posted:
            // the full refresh must flash the new content, not the app we came back from.
            val waited = picture != null && picture != oldPicture
            val inTime = !waited || withTimeoutOrNull(2_500) { settled.await() } != null
            // Give «Манга» up to MANGA_WAIT_MS after the books phase to land in the same refreshed
            // frame (a warm provider answers in 0.25–0.5 s); a cold one merges later, unflashed.
            val waitLeft = MANGA_WAIT_MS - (System.nanoTime() - booksAt) / 1_000_000
            val full = if (load.isCompleted) load.await() else if (waitLeft > 0) withTimeoutOrNull(waitLeft) { load.await() } else null
            if (full != null) state.data = full
            awaitFrameDrawn()
            awaitFrameDrawn()
            Log.i(TAG, "home ready in ${(System.nanoTime() - t0) / 1_000_000} ms (picture settled: $inTime, manga: ${full != null}); full refresh")
            Eink.fullRefresh(this@MainActivity)
            if (full == null) {
                state.data = load.await()
                Log.i(TAG, "manga merged in ${(System.nanoTime() - t0) / 1_000_000} ms (after the full refresh)")
            }
        }
    }

    /** «Манга» changed while we are started: reload data only (no new picture, no flash). */
    private fun quietRefresh() {
        if (startJob?.isActive == true) return
        quietJob?.cancel()
        quietJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { loader.load() }
            state.data = data
        }
    }

    private suspend fun awaitFrameDrawn() = suspendCancellableCoroutine { cont ->
        Choreographer.getInstance().postFrameCallback {
            // Runs before this frame's traversal; the post lands after the frame is drawn.
            window.decorView.post { if (cont.isActive) cont.resume(Unit) }
        }
    }

    /**
     * An app was installed / removed / changed: rebuild the all-apps list (if it is open) and the
     * dock. Dynamic receiver; system broadcasts need the exported form on Android 11.
     */
    private fun registerPackageReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                lifecycleScope.launch {
                    val (list, dock) = withContext(Dispatchers.IO) { apps.all() to apps.dockSlots() }
                    state.allApps = list
                    state.dock = dock
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
        packageReceiver = receiver
    }

    private fun nextDecor(): String {
        val all = catgirlDecors(assets)
        if (all.isEmpty()) return ""
        val i = (prefs.getInt(PREF_DECOR, -1) + 1).mod(all.size)
        prefs.edit().putInt(PREF_DECOR, i).apply()
        return all[i]
    }

    // ---- storage permission (ruling R6) -------------------------------------------------------

    private fun hasStorage(): Boolean =
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The card's «Разрешить» button. */
    override fun requestStorage() = askStorage(fromCard = true)

    /** Automatic asks never leave home; only the card may open the app's settings page. */
    private fun askStorage(fromCard: Boolean) {
        val action = StorageAccess.decide(
            fromCard = fromCard,
            askedBefore = prefs.getBoolean(PREF_ASKED, false),
            rationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE),
        )
        when (action) {
            StorageAccess.Action.REQUEST -> storageRequest.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
            StorageAccess.Action.SETTINGS -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            StorageAccess.Action.NONE -> Unit // the card stays on screen
        }
    }

    // ---- HomeActions --------------------------------------------------------------------------

    override fun openBook(book: Book) {
        Launch.openBook(this, book.file)
    }

    override fun openManga(manga: Manga) {
        Launch.openManga(this, manga.id)
    }

    override fun openApp(app: AppEntry) {
        state.overlay = null
        Launch.openApp(this, app.pkg)
    }

    /** Long-press in all apps: the app's system details page (uninstall, storage, permissions). */
    override fun appDetails(app: AppEntry) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "app details for ${app.pkg}", it) }
    }

    override fun selectTab(tab: ShelfTab) {
        state.tab = tab
        state.nav.page = 0
        prefs.edit().putString(PREF_TAB, tab.name).apply()
    }

    override fun fullRefresh() = Eink.fullRefresh(this)

    override fun pictureSettled() {
        if (pictureSettled?.isCompleted == false) Log.i(TAG, "picture settled in ${(System.nanoTime() - refreshStart) / 1_000_000} ms")
        pictureSettled?.complete(Unit)
    }

    override fun showAllApps() = showApps(Overlay.AllApps)

    override fun showStats() {
        // Open on the current year (the newest page).
        state.statsNav.page = Int.MAX_VALUE
        state.overlay = Overlay.Stats
    }

    override fun showAbout() {
        state.overlay = Overlay.About
    }

    override fun pickDock(index: Int) = showApps(Overlay.DockPick(index))

    private fun showApps(overlay: Overlay) {
        if (overlay == Overlay.AllApps) state.appsNav.page = 0
        state.overlay = overlay
        lifecycleScope.launch {
            state.allApps = withContext(Dispatchers.IO) { apps.all() }
        }
    }

    override fun setDock(index: Int, app: AppEntry) {
        apps.setDock(index, app.pkg)
        state.overlay = null
        lifecycleScope.launch { state.dock = withContext(Dispatchers.IO) { apps.dockSlots() } }
    }

    override fun closeOverlay() {
        state.overlay = null
    }

    // ---- keys ---------------------------------------------------------------------------------

    /** Page keys, volume keys and DPAD left/right page the shelf while the home screen is shown. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val nav = when (state.overlay) {
            null -> state.nav
            Overlay.AllApps -> state.appsNav
            Overlay.Stats -> state.statsNav
            else -> null
        }
        if (nav != null) {
            val forward = when (event.keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, HAOQING_PAGE_DOWN -> true
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT, HAOQING_PAGE_UP -> false
                else -> null
            }
            if (forward != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (forward) nav.next() else nav.prev()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val TAG = "Home"
        /**
         * The E6's hardware page keys (scan codes 603/604) are mapped by the firmware's Generic.kl
         * to its own key labels; resolve their key codes at runtime (-1 = not this firmware).
         */
        val HAOQING_PAGE_UP = KeyEvent.keyCodeFromString("HAOQING_PAGE_UP").takeIf { it > 0 } ?: -1
        val HAOQING_PAGE_DOWN = KeyEvent.keyCodeFromString("HAOQING_PAGE_DOWN").takeIf { it > 0 } ?: -1
        /** How long the full refresh waits for the «Манга» phase after the books phase. */
        const val MANGA_WAIT_MS = 1_000L
        const val PREF_TAB = "shelf_tab"
        const val PREF_DECOR = "decor_index"
        const val PREF_ASKED = "storage_asked"
    }
}
