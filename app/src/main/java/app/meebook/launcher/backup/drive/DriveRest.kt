package app.meebook.launcher.backup.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Google Drive REST v3 over [HttpURLConnection]. Blocking: call off the main thread.
 * The access token only ever travels in the Authorization header; it is never put in a
 * URL, log line or exception message.
 */
class DriveRest(
    private val baseUrl: String = "https://www.googleapis.com",
    private val token: () -> String,
) : Drive {

    override fun folder(name: String, parent: String?): String {
        val p = parent ?: "root"
        val q = "name = '${esc(name)}' and mimeType = '$FOLDER_MIME' and '${esc(p)}' in parents and trashed = false"
        search(q).firstOrNull()?.let { return it.id }
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(p))
            .toString().toByteArray(Charsets.UTF_8)
        return json(open("POST", "$baseUrl/drive/v3/files?fields=id", body)).getString("id")
    }

    override fun list(parent: String): List<RemoteFile> =
        search("'${esc(parent)}' in parents and trashed = false")

    override fun listByProp(key: String, value: String): List<RemoteFile> =
        search("appProperties has { key='${esc(key)}' and value='${esc(value)}' } and trashed = false")

    override fun upload(
        parent: String,
        name: String,
        file: File,
        props: Map<String, String>,
        resume: ResumeStore?,
    ): RemoteFile {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
        if (props.isNotEmpty()) meta.put("appProperties", JSONObject(props))
        return if (file.length() > THRESHOLD) uploadResumable(parent, name, file, meta, resume)
        else uploadMultipart(file, meta)
    }

    override fun download(id: String, to: File) {
        val part = File(to.path + ".part")
        val c = connect("GET", "$baseUrl/drive/v3/files/${enc(id)}?alt=media")
        try {
            check(c)
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(to)) {
                to.delete()
                if (!part.renameTo(to)) throw IOException("Cannot move download into place")
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    override fun delete(id: String) {
        open("DELETE", "$baseUrl/drive/v3/files/${enc(id)}", null)
    }

    // ---- uploads -------------------------------------------------------------------------

    private fun uploadMultipart(file: File, meta: JSONObject): RemoteFile {
        val boundary = "meebook-" + UUID.randomUUID()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta.toString() + "\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n")
            .toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val c = connect("POST", "$baseUrl/upload/drive/v3/files?uploadType=multipart&fields=$FILE_FIELDS")
        try {
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            c.doOutput = true
            c.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
            c.outputStream.use { out ->
                out.write(head)
                file.inputStream().use { it.copyTo(out) }
                out.write(tail)
            }
            check(c)
            return parseFile(readJson(c))
        } finally {
            c.disconnect()
        }
    }

    private fun uploadResumable(
        parent: String,
        name: String,
        file: File,
        meta: JSONObject,
        resume: ResumeStore?,
    ): RemoteFile {
        val key = "$parent|$name"
        val total = file.length()
        var session: String
        var offset: Long
        val stored = resume?.get(key)
        if (stored != null && stored.second in 0 until total) {
            session = stored.first; offset = stored.second
        } else {
            session = startSession(meta, total); offset = 0
            resume?.put(key, session, 0)
        }
        var restarted = false
        while (true) {
            val end = minOf(offset + CHUNK, total)
            val c = connect("PUT", session)
            try {
                c.setRequestProperty("Content-Range", "bytes $offset-${end - 1}/$total")
                c.doOutput = true
                c.setFixedLengthStreamingMode(end - offset)
                c.outputStream.use { copyRange(file, offset, end - offset, it) }
                val code = c.responseCode
                when {
                    code == 200 || code == 201 -> {
                        val r = parseFile(readJson(c))
                        resume?.clear(key)
                        return r
                    }
                    code == 308 -> {
                        offset = c.getHeaderField("Range")?.substringAfter('-', "")?.toLongOrNull()?.plus(1) ?: 0L
                        resume?.put(key, session, offset)
                    }
                    (code == 404 || code == 410) && !restarted -> {
                        // Session expired on the server: begin again once.
                        restarted = true
                        resume?.clear(key)
                        session = startSession(meta, total); offset = 0
                        resume?.put(key, session, 0)
                    }
                    else -> check(c)
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private fun startSession(meta: JSONObject, total: Long): String {
        val body = meta.toString().toByteArray(Charsets.UTF_8)
        val c = connect("POST", "$baseUrl/upload/drive/v3/files?uploadType=resumable&fields=$FILE_FIELDS")
        try {
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            c.setRequestProperty("X-Upload-Content-Length", total.toString())
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            check(c)
            return c.getHeaderField("Location") ?: throw DriveException(c.responseCode, "Drive gave no upload session")
        } finally {
            c.disconnect()
        }
    }

    private fun copyRange(file: File, from: Long, length: Long, out: OutputStream) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("File shrank during upload")
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    // ---- plumbing ------------------------------------------------------------------------

    private fun search(q: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            var url = "$baseUrl/drive/v3/files?q=${enc(q)}&spaces=drive&pageSize=1000" +
                "&fields=${enc("nextPageToken,files(id,name,size,appProperties)")}"
            if (page != null) url += "&pageToken=${enc(page)}"
            val j = json(open("GET", url, null))
            j.optJSONArray("files")?.let { for (i in 0 until it.length()) out += parseFile(it.getJSONObject(i)) }
            page = j.optString("nextPageToken", "").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** Sends a request with an optional JSON body and returns the response text ("" if none). */
    private fun open(method: String, url: String, body: ByteArray?): String {
        val c = connect(method, url)
        try {
            if (body != null) {
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            check(c)
            return c.inputStream.use { readAll(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun connect(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer ${token()}")
        return c
    }

    /** Throws for any non-2xx response, with a message that carries the status only. */
    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 200..299) return
        runCatching { c.errorStream?.close() }
        if (code == 401) throw DriveAuthException("Google Drive rejected the access token (HTTP 401)")
        throw DriveException(code, "Google Drive request failed (HTTP $code)")
    }

    private fun readJson(c: HttpURLConnection): JSONObject = json(c.inputStream.use { readAll(it) })

    private fun json(text: String): JSONObject = JSONObject(text)

    private fun readAll(input: InputStream): String = input.readBytes().toString(Charsets.UTF_8)

    private fun parseFile(j: JSONObject): RemoteFile {
        val props = HashMap<String, String>()
        j.optJSONObject("appProperties")?.let { p -> p.keys().forEach { props[it] = p.getString(it) } }
        return RemoteFile(j.getString("id"), j.optString("name"), j.optString("size", "0").toLongOrNull() ?: 0L, props)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val FILE_FIELDS = "id,name,size,appProperties"
        const val THRESHOLD = 5L * 1024 * 1024
        const val CHUNK = 5L * 1024 * 1024 // multiple of 256 KiB
    }
}
