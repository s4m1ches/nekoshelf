package app.nekoshelf.launcher.backup

import android.content.Context
import android.content.SharedPreferences
import app.nekoshelf.launcher.backup.drive.ResumeStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * [BackupStatus] (one key per field) and the Drive resumable-upload sessions (keys under
 * [RESUME_PREFIX]) in SharedPreferences `backup`. Writes are synchronous ([SharedPreferences.Editor.commit]):
 * the backup worker may be killed right after a step. Call off the main thread.
 */
class BackupStore(context: Context) : ResumeStore {
    private val prefs = context.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun load(): BackupStatus = synchronized(LOCK) {
        BackupStatus(
            signedIn = prefs.getBoolean(SIGNED_IN, false),
            account = prefs.getString(ACCOUNT, null),
            lastSuccessAt = long(LAST_SUCCESS_AT),
            lastFailureAt = long(LAST_FAILURE_AT),
            lastError = prefs.getString(LAST_ERROR, null),
            failedAutoDay = prefs.getString(FAILED_AUTO_DAY, null),
            failedAutoAttempts = prefs.getInt(FAILED_AUTO_ATTEMPTS, 0),
            restoreOfferPending = prefs.getBoolean(RESTORE_OFFER_PENDING, false),
            koreaderSnapshots = prefs.getInt(KOREADER_SNAPSHOTS, 0),
            mangaSnapshots = prefs.getInt(MANGA_SNAPSHOTS, 0),
            booksOnDrive = prefs.getInt(BOOKS_ON_DRIVE, 0),
            booksLocal = prefs.getInt(BOOKS_LOCAL, 0),
            booksPending = prefs.getInt(BOOKS_PENDING, 0),
            booksCaughtUpOnce = prefs.getBoolean(BOOKS_CAUGHT_UP_ONCE, false),
            signedInAt = long(SIGNED_IN_AT),
            inboxEnabled = prefs.getBoolean(INBOX_ENABLED, false),
            inboxLastCheckAt = long(INBOX_LAST_CHECK_AT),
            inboxReceived = prefs.getInt(INBOX_RECEIVED, 0),
            inboxError = prefs.getString(INBOX_ERROR, null),
            inboxNeedsReauth = prefs.getBoolean(INBOX_NEEDS_REAUTH, false),
            inboxLastReceived = prefs.getInt(INBOX_LAST_RECEIVED, 0),
            inboxLastReceivedAt = long(INBOX_LAST_RECEIVED_AT),
        )
    }

    fun save(s: BackupStatus) {
        synchronized(LOCK) {
            prefs.edit()
                .putBoolean(SIGNED_IN, s.signedIn)
                .putString(ACCOUNT, s.account)
                .putOrRemove(LAST_SUCCESS_AT, s.lastSuccessAt)
                .putOrRemove(LAST_FAILURE_AT, s.lastFailureAt)
                .putString(LAST_ERROR, s.lastError)
                .remove(OLD_LAST_ATTEMPT_DAY)
                .putString(FAILED_AUTO_DAY, s.failedAutoDay)
                .putInt(FAILED_AUTO_ATTEMPTS, s.failedAutoAttempts)
                .putBoolean(RESTORE_OFFER_PENDING, s.restoreOfferPending)
                .putInt(KOREADER_SNAPSHOTS, s.koreaderSnapshots)
                .putInt(MANGA_SNAPSHOTS, s.mangaSnapshots)
                .putInt(BOOKS_ON_DRIVE, s.booksOnDrive)
                .putInt(BOOKS_LOCAL, s.booksLocal)
                .putInt(BOOKS_PENDING, s.booksPending)
                .putBoolean(BOOKS_CAUGHT_UP_ONCE, s.booksCaughtUpOnce)
                .putOrRemove(SIGNED_IN_AT, s.signedInAt)
                .putBoolean(INBOX_ENABLED, s.inboxEnabled)
                .putOrRemove(INBOX_LAST_CHECK_AT, s.inboxLastCheckAt)
                .putInt(INBOX_RECEIVED, s.inboxReceived)
                .putString(INBOX_ERROR, s.inboxError)
                .putBoolean(INBOX_NEEDS_REAUTH, s.inboxNeedsReauth)
                .putInt(INBOX_LAST_RECEIVED, s.inboxLastReceived)
                .putOrRemove(INBOX_LAST_RECEIVED_AT, s.inboxLastReceivedAt)
                .commit()
        }
    }

    /** Applies [change] to the stored status atomically (within this process) and returns the result. */
    fun update(change: (BackupStatus) -> BackupStatus): BackupStatus = synchronized(LOCK) {
        change(load()).also(::save)
    }

    /** The owner signed in: a failure from before (e.g. the revoked-access error) no longer counts. */
    fun signedIn(account: String?, nowMs: Long) {
        update { it.copy(signedIn = true, account = account, signedInAt = nowMs, lastFailureAt = null, lastError = null) }
    }

    /**
     * The «Забирать новые книги» checkbox. Turning it on (the readonly grant was just given) also
     * clears [BackupStatus.inboxNeedsReauth], so the wake checks the inbox again.
     */
    fun inboxEnabled(enabled: Boolean) {
        update { it.copy(inboxEnabled = enabled, inboxNeedsReauth = if (enabled) false else it.inboxNeedsReauth) }
    }

    /** Forgets the account, its Drive counts and any upload sessions (they belong to that account). */
    fun signedOut() {
        synchronized(LOCK) {
            save(BackupStatus.EMPTY)
            clearResumeSessions()
        }
    }

    // ---- inbox "seen" set: Drive file ids the inbox has handled (local cache of `inbox-seen.json`) ----

    /**
     * Ids the inbox must never download again. Kept across sign-out and the checkbox going off/on
     * (Drive file ids are unique, so another account's ids never match).
     */
    fun inboxSeen(): Set<String> = synchronized(LOCK) { HashSet(prefs.getStringSet(INBOX_SEEN, null) ?: emptySet()) }

    fun saveInboxSeen(ids: Set<String>) {
        synchronized(LOCK) { prefs.edit().putStringSet(INBOX_SEEN, HashSet(ids)).commit() }
    }

    // ---- inbox "received paths": where the inbox put (or found) a book, local cache of `inbox-seen.json` `paths` ----

    /**
     * Relative paths (to the Drive file's size) of books the inbox received or found already on the
     * reader, recorded under [account]: the backup counts a local book at such a path as on Drive
     * (ruling I9). Kept across sign-out and the checkbox going off/on; another account's paths are
     * not returned (paths, unlike Drive ids, may repeat between accounts).
     */
    fun inboxReceivedPaths(account: String?): Map<String, Long> = synchronized(LOCK) {
        val o = try {
            JSONObject(prefs.getString(INBOX_PATHS, null) ?: return emptyMap())
        } catch (e: JSONException) {
            return emptyMap()
        }
        if (o.optString(PATHS_ACCOUNT).ifEmpty { null } != account) return emptyMap()
        receivedPathsOf(o)
    }

    /** Merges [paths] into the received paths of [account] (a newer size wins); another account's are replaced. */
    fun addInboxReceivedPaths(account: String?, paths: Map<String, Long>) {
        synchronized(LOCK) {
            val merged = inboxReceivedPaths(account) + paths
            val o = JSONObject().put(PATHS_ACCOUNT, account ?: "").put(PATHS, receivedPathsJson(merged))
            prefs.edit().putString(INBOX_PATHS, o.toString()).commit()
        }
    }

    // ---- ResumeStore: value "<offset>|<url>" (the offset never contains '|'; the URL may) ----

    override fun get(key: String): Pair<String, Long>? {
        val v = prefs.getString(RESUME_PREFIX + key, null) ?: return null
        val offset = v.substringBefore('|').toLongOrNull() ?: return null
        val url = v.substringAfter('|', "").ifEmpty { return null }
        return url to offset
    }

    override fun put(key: String, url: String, offset: Long) {
        prefs.edit().putString(RESUME_PREFIX + key, "$offset|$url").commit()
    }

    override fun clear(key: String) {
        prefs.edit().remove(RESUME_PREFIX + key).commit()
    }

    /** Drops every stored session: after a successful run none is mid-upload, and old-key orphans go too. */
    fun clearResumeSessions() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(RESUME_PREFIX) }.forEach { e.remove(it) }
        e.commit()
    }

    private fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOrRemove(key: String, v: Long?): SharedPreferences.Editor =
        if (v == null) remove(key) else putLong(key, v)

    private companion object {
        val LOCK = Any()
        const val RESUME_PREFIX = "resume:"
        const val SIGNED_IN = "signedIn"
        const val ACCOUNT = "account"
        const val LAST_SUCCESS_AT = "lastSuccessAt"
        const val LAST_FAILURE_AT = "lastFailureAt"
        const val LAST_ERROR = "lastError"
        /** v1.1 builds before R32 wrote it; never read. */
        const val OLD_LAST_ATTEMPT_DAY = "lastAttemptDay"
        const val FAILED_AUTO_DAY = "failedAutoDay"
        const val FAILED_AUTO_ATTEMPTS = "failedAutoAttempts"
        const val RESTORE_OFFER_PENDING = "restoreOfferPending"
        const val KOREADER_SNAPSHOTS = "koreaderSnapshots"
        const val MANGA_SNAPSHOTS = "mangaSnapshots"
        const val BOOKS_ON_DRIVE = "booksOnDrive"
        const val BOOKS_LOCAL = "booksLocal"
        const val BOOKS_PENDING = "booksPending"
        const val BOOKS_CAUGHT_UP_ONCE = "booksCaughtUpOnce"
        const val SIGNED_IN_AT = "signedInAt"
        const val INBOX_ENABLED = "inboxEnabled"
        const val INBOX_LAST_CHECK_AT = "inboxLastCheckAt"
        const val INBOX_RECEIVED = "inboxReceived"
        const val INBOX_ERROR = "inboxError"
        const val INBOX_NEEDS_REAUTH = "inboxNeedsReauth"
        const val INBOX_SEEN = "inboxSeen"
        const val INBOX_LAST_RECEIVED = "inboxLastReceived"
        const val INBOX_LAST_RECEIVED_AT = "inboxLastReceivedAt"
        const val INBOX_PATHS = "inboxPaths"
        const val PATHS_ACCOUNT = "account"
    }
}

/** `"paths"` of [o] (an `inbox-seen.json` or the local cache): `[{"path":…,"size":…}]`; missing or malformed entries are left out. */
internal fun receivedPathsOf(o: JSONObject): Map<String, Long> {
    val arr = o.optJSONArray(PATHS) ?: return emptyMap()
    val out = HashMap<String, Long>()
    for (i in 0 until arr.length()) {
        val e = arr.optJSONObject(i) ?: continue
        val path = e.optString("path").ifEmpty { null } ?: continue
        out[path] = e.optLong("size", 0L)
    }
    return out
}

/** The `"paths"` value for [paths], sorted by path. */
internal fun receivedPathsJson(paths: Map<String, Long>): JSONArray =
    JSONArray(paths.entries.sortedBy { it.key }.map { (p, n) -> JSONObject().put("path", p).put("size", n) })

internal const val PATHS = "paths"
