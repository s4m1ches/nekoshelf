package app.nekoshelf.launcher.backup

import app.nekoshelf.launcher.backup.drive.Drive
import app.nekoshelf.launcher.backup.drive.RemoteFile

const val INBOX_ERROR_MANY_ROOTS = "на Диске несколько папок Nekoshelf"

/**
 * App-created file in the `Nekoshelf` root: `{"ids":[…],"paths":[{"path":…,"size":…}],"updated":<ms>}`,
 * the seen set and the received paths that survive a reader wipe (files written before paths were
 * kept have no `paths`).
 */
internal const val INBOX_SEEN_FILE = "inbox-seen.json"
internal const val KIND_INBOX_SEEN = "inbox-seen"

/** The backup's snapshot folder and file kind (see [BackupRunner]): proof that a `Nekoshelf` folder is the backup's. */
private const val KOREADER_FOLDER = "koreader"
private const val KIND_KOREADER = "koreader"

internal const val FOLDER_MIME = "application/vnd.google-apps.folder"

internal fun RemoteFile.isFolder() = mimeType == FOLDER_MIME

/** Several `Nekoshelf` folders in My Drive and none (or more than one) proven to be the backup's. */
class AmbiguousRootException : Exception(INBOX_ERROR_MANY_ROOTS)

/**
 * Finds the backup's `Nekoshelf` folder in My Drive; the backup and the inbox both use it, so they
 * always agree. With the readonly grant the owner's own folders of that name are visible too, so:
 * only folders count; a lone folder is taken as is; among several, the one proven by the app's own
 * markers (an app-made `inbox-seen.json`, or a `koreader` folder holding a `kind=koreader` snapshot)
 * is used, and none or more than one proven throws [AmbiguousRootException]. Null when there is
 * none (creating it is the caller's business).
 */
object DriveRoots {
    fun resolve(drive: Drive): RemoteFile? {
        val folders = drive.findChildren("root", DRIVE_ROOT).filter { it.isFolder() }
        if (folders.size <= 1) return folders.firstOrNull()
        return folders.filter { isBackupRoot(drive, it.id) }.singleOrNull() ?: throw AmbiguousRootException()
    }

    /** App-made seen files only: a file the owner happened to name so is neither trusted nor deleted. */
    internal fun seenCopies(drive: Drive, rootId: String): List<RemoteFile> =
        drive.findChildren(rootId, INBOX_SEEN_FILE).filter { !it.isFolder() && it.props[PROP_KIND] == KIND_INBOX_SEEN }

    private fun isBackupRoot(drive: Drive, id: String): Boolean =
        seenCopies(drive, id).isNotEmpty() ||
            drive.findChildren(id, KOREADER_FOLDER).filter { it.isFolder() }
                .any { k -> drive.list(k.id).any { it.props[PROP_KIND] == KIND_KOREADER } }
}
