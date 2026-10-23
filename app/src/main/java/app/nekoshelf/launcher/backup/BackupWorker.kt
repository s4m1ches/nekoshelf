package app.nekoshelf.launcher.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.DriveRest
import app.nekoshelf.launcher.backup.drive.GoogleAuth
import app.nekoshelf.launcher.backup.drive.TokenUnavailableException
import app.nekoshelf.launcher.data.koreader.KoreaderPaths
import app.nekoshelf.launcher.data.manga.MangaBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Runs one backup. Always succeeds as far as WorkManager is concerned: failures are recorded in the
 * [BackupStatus], and a retry/backoff would be the timed polling the app does not do; the next wake
 * tries again. While a restore offer or a restore holds backups it does nothing at all (not even ask
 * for a token). A null token means the owner must sign in again; a token that is merely unavailable
 * for now ([app.nekoshelf.launcher.backup.drive.TokenUnavailableException]) is recorded as «нет сети».
 *
 * With «Забирать новые книги» on, it also asks for a token with `drive.readonly`: given, the run
 * gets the inbox step and the readonly view ([backupRunner]); not given (null: the grant is gone,
 * or Play services failing for now), this run goes on as with the checkbox off and only the
 * inbox's error is recorded ([recordInboxFailure], a [DriveAuthException] for a missing grant).
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val manual = inputData.getBoolean(BackupTrigger.KEY_MANUAL, false)
        val store = BackupStore(app)
        if (store.load().restoreOfferPending) return@withContext Result.success()
        try {
            val token = tokenSource(app)
            if (token() == null) {
                fail(store, BACKUP_ERROR_AUTH, countsForAuto = !manual)
            } else {
                val readonly = if (store.load().inboxEnabled) readonlyToken(app, store) else null
                val power = { BackupTrigger.readPower(app) }
                runnerFactory(app, store, token, readonly).run(power(), manual, power)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(store, backupErrorText(e), countsForAuto = !manual && !isNetworkClass(e))
        }
        Result.success()
    }

    /** The readonly token source when it gives a token now; otherwise null, with the inbox's error recorded. */
    private fun readonlyToken(app: Context, store: BackupStore): (() -> String?)? {
        val source = readonlyTokenSource(app)
        val failure = try {
            if (source() != null) return source
            DriveAuthException("no drive.readonly token without the owner")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        recordInboxFailure(store, failure)
        return null
    }

    /** Records a failure outside the runner; [countsForAuto] adds it to today's failed automatic runs (R32). */
    private fun fail(store: BackupStore, text: String, countsForAuto: Boolean) {
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        store.update {
            val s = it.copy(lastFailureAt = now, lastError = text)
            if (countsForAuto) s.countFailedAuto(today) else s
        }
    }

    internal companion object {
        /** Test hooks: where the access tokens come from (drive.file, and drive.file + drive.readonly), and how the runner is built. */
        var tokenSource: (Context) -> (() -> String?) = { GoogleAuth(it)::silentToken }
        var readonlyTokenSource: (Context) -> (() -> String?) = { GoogleAuth(it).let { a -> { a.silentToken(withReadonly = true) } } }
        var runnerFactory: (Context, BackupStore, () -> String?, (() -> String?)?) -> BackupRunner = ::backupRunner
    }
}

/** KOReader's folder on the reader's storage. */
fun koreaderRoot(): File = File(Environment.getExternalStorageDirectory(), "koreader")

/** KOReader's books home folder (where the books and their `.sdr` folders live). Blocking. */
fun koreaderHomeDir(): File = KoreaderPaths(koreaderRoot()).homeDir()

/**
 * The runner on the real Drive, KOReader folders and «Манга» bridge, as the worker and the backup
 * screen (sign-in check, restore) both use it. [token] (drive.file) is asked for before every Drive
 * call.
 *
 * [readonlyToken] (drive.file + drive.readonly; non-null only while the inbox checkbox is on and the
 * grant was given) adds the inbox step and the readonly view: the backup's Drive then asks it first
 * ([ReadonlyFallbackToken]: should it stop giving one mid-run, [token] carries on so a lost grant
 * never fails the backup, and the runner uploads no books for that run), and the inbox's own Drive
 * asks only it (none → [DriveAuthException], the inbox's error).
 */
fun backupRunner(app: Context, store: BackupStore, token: () -> String?, readonlyToken: (() -> String?)? = null): BackupRunner {
    val inbox = readonlyToken?.let { inboxRunner(store, it) }
    val backupToken = readonlyToken?.let { ReadonlyFallbackToken(it, token) }
    return BackupRunner(
        drive = driveOn(backupToken ?: token),
        store = store,
        koreaderRoot = koreaderRoot(),
        homeDir = ::koreaderHomeDir,
        mangaBackup = { MangaBridge(app).backup() },
        mangaAvailable = { MangaBridge(app).available() },
        cacheDir = app.cacheDir,
        clock = System::currentTimeMillis,
        zone = ZoneId.systemDefault(),
        inbox = inbox,
        unmetered = { isUnmetered(app) },
        readonly = readonlyToken != null,
        readonlyLost = { backupToken?.lost },
    )
}

/** Drive on [token]; a missing token is a [DriveAuthException]. */
private fun driveOn(token: () -> String?) = DriveRest(token = { token() ?: throw DriveAuthException("token unavailable") })

/**
 * The inbox on the real Drive and the KOReader books home, asking only the [readonlyToken]
 * (none → [DriveAuthException]); shared by the backup's inbox step and [InboxWorker].
 */
fun inboxRunner(store: BackupStore, readonlyToken: () -> String?): InboxRunner =
    InboxRunner(driveOn(readonlyToken), store, ::koreaderHomeDir, { it.usableSpace }, System::currentTimeMillis)

/**
 * The backup's token while the readonly grant is in use: the [readonly] one; once that gives none
 * (the grant gone) or Play services fail for it ([TokenUnavailableException]), the [base]
 * drive.file one for the rest of the run, with [lost] saying why the readonly view is gone.
 */
internal class ReadonlyFallbackToken(
    private val readonly: () -> String?,
    private val base: () -> String?,
) : () -> String? {
    @Volatile var lost: Exception? = null
        private set

    override fun invoke(): String? {
        if (lost == null) {
            try {
                readonly()?.let { return it }
                lost = DriveAuthException("no drive.readonly token mid-run")
            } catch (e: TokenUnavailableException) {
                lost = e
            }
        }
        return base()
    }
}

/** Whether the active network is unmetered (Wi-Fi as a rule); false when unknown. */
fun isUnmetered(app: Context): Boolean = try {
    val cm = app.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
} catch (e: Exception) {
    false
}
