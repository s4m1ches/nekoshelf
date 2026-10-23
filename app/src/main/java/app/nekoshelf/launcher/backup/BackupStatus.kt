package app.nekoshelf.launcher.backup

/** Persisted state of the Drive backup (see BackupStore). */
data class BackupStatus(
    val signedIn: Boolean,
    val account: String?,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastError: String?,
    /** Local day (`YYYY-MM-DD`) that [failedAutoAttempts] counts for (ruling R32). */
    val failedAutoDay: String?,
    /** Automatic runs that failed on [failedAutoDay], network-class failures not counted. */
    val failedAutoAttempts: Int,
    val restoreOfferPending: Boolean,
    val koreaderSnapshots: Int,
    val mangaSnapshots: Int,
    val booksOnDrive: Int,
    val booksLocal: Int,
    val booksPending: Int,
    val booksCaughtUpOnce: Boolean,
    val signedInAt: Long?,
    /** «Забирать новые книги с Диска» is on (the readonly grant was given). */
    val inboxEnabled: Boolean = false,
    /** When the inbox last checked `Nekoshelf/books`. */
    val inboxLastCheckAt: Long? = null,
    /** Books the last inbox check downloaded. */
    val inboxReceived: Int = 0,
    /** The last inbox check's error text; null when it went fine. */
    val inboxError: String? = null,
    /**
     * A sign-in failure (HTTP 401 / [app.nekoshelf.launcher.backup.drive.DriveAuthException], never a
     * 403 or error text) ended an inbox run: the wake skips the inbox until the owner turns the
     * checkbox on again ([BackupStore.inboxEnabled]).
     */
    val inboxNeedsReauth: Boolean = false,
) {
    companion object {
        val EMPTY = BackupStatus(
            signedIn = false,
            account = null,
            lastSuccessAt = null,
            lastFailureAt = null,
            lastError = null,
            failedAutoDay = null,
            failedAutoAttempts = 0,
            restoreOfferPending = false,
            koreaderSnapshots = 0,
            mangaSnapshots = 0,
            booksOnDrive = 0,
            booksLocal = 0,
            booksPending = 0,
            booksCaughtUpOnce = false,
            signedInAt = null,
            inboxEnabled = false,
            inboxLastCheckAt = null,
            inboxReceived = 0,
            inboxError = null,
            inboxNeedsReauth = false,
        )
    }
}
