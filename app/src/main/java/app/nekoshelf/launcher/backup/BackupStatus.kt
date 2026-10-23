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
        )
    }
}
