package app.nekoshelf.launcher.backup.drive

import java.io.File
import java.io.IOException

data class RemoteFile(
    val id: String,
    val name: String,
    val size: Long,
    val props: Map<String, String>,
    val mimeType: String = "",
    /** Last modification, epoch milliseconds; 0 when unknown. */
    val modifiedTime: Long = 0L,
    val parents: List<String> = emptyList(),
)

/** A non-folder file found by [Drive.listTree]; [relativePath] is `/`-separated, folder names below the root plus the file name. */
data class TreeFile(val file: RemoteFile, val relativePath: String)

/** Persists a resumable-upload session (URL + committed offset) so an interrupted upload can continue. */
interface ResumeStore {
    fun get(key: String): Pair<String, Long>?
    fun put(key: String, url: String, offset: Long)
    fun clear(key: String)
}

/** Token rejected (HTTP 401): the caller should re-authorize. */
class DriveAuthException(message: String) : Exception(message)

/**
 * Google Play services gave no access token for a reason other than "the owner must sign in
 * again" (a timeout, no network, Play services busy or interrupted). Network-class: the backup
 * shows «нет сети» and tries again at the next wake. The message never contains a token.
 */
class TokenUnavailableException(message: String, cause: Throwable?) : IOException(message, cause)

/** Any other failed Drive call. The message never contains the access token. */
class DriveException(val code: Int, message: String) : Exception(message)

interface Drive {
    /** Finds a non-trashed folder [name] under [parent] (null = Drive root) or creates it. Returns its id. */
    fun folder(name: String, parent: String?): String
    fun list(parent: String): List<RemoteFile>
    fun listByProp(key: String, value: String): List<RemoteFile>

    /** Every non-trashed, non-folder descendant of [rootId], walked breadth-first. */
    fun listTree(rootId: String): List<TreeFile>

    /** A non-trashed child of [parent] named [name], whoever created it; null when absent. */
    fun findChild(parent: String, name: String): RemoteFile?

    /**
     * Uploads [file] as [name] under [parent]. Files over 5 MB use a resumable session whose URL and
     * committed offset are kept in [resume] under the key `"$parent|$name"`.
     */
    fun upload(parent: String, name: String, file: File, props: Map<String, String>, resume: ResumeStore?): RemoteFile
    fun download(id: String, to: File)
    fun delete(id: String)
}
