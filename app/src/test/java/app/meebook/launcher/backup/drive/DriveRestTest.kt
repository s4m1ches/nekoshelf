package app.meebook.launcher.backup.drive

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
class DriveRestTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var drive: DriveRest

    private val token = "fake-access-token-123"
    private val mb5 = 5L * 1024 * 1024

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        drive = DriveRest(server.url("/").toString().trimEnd('/')) { token }
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun decodedQuery(path: String) = URLDecoder.decode(path.substringAfter('?'), "UTF-8")
    private fun sparse(name: String, size: Long): File =
        tmp.newFile(name).also { f -> RandomAccessFile(f, "rw").use { it.setLength(size) } }

    @Test fun folderFindsExistingOrCreates() {
        server.enqueue(json("""{"files":[{"id":"F1","name":"Books"}]}"""))
        assertEquals("F1", drive.folder("Books", "P"))
        val find = server.takeRequest()
        assertEquals("GET", find.method)
        assertEquals("/drive/v3/files", find.path!!.substringBefore('?'))
        assertEquals("Bearer $token", find.getHeader("Authorization"))
        val q = decodedQuery(find.path!!)
        assertTrue(q, q.contains("q=name = 'Books' and mimeType = 'application/vnd.google-apps.folder' and 'P' in parents and trashed = false"))
        assertTrue(q, q.contains("spaces=drive"))

        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(json("""{"id":"NEW"}"""))
        assertEquals("NEW", drive.folder("It's", null))
        val find2 = server.takeRequest()
        assertTrue(decodedQuery(find2.path!!).contains("name = 'It\\'s'"))
        assertTrue(decodedQuery(find2.path!!).contains("'root' in parents"))
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/drive/v3/files", create.path!!.substringBefore('?'))
        val body = JSONObject(create.body.readUtf8())
        assertEquals("It's", body.getString("name"))
        assertEquals("application/vnd.google-apps.folder", body.getString("mimeType"))
        assertEquals("root", body.getJSONArray("parents").getString(0))
    }

    @Test fun listByPropPagesThrough() {
        server.enqueue(json("""{"nextPageToken":"T2","files":[{"id":"a","name":"a.epub","size":"10","appProperties":{"path":"x/a"}}]}"""))
        server.enqueue(json("""{"files":[{"id":"b","name":"b.epub","size":"20","appProperties":{"path":"x/b"}}]}"""))
        val r = drive.listByProp("path", "x/a")
        assertEquals(listOf("a", "b"), r.map { it.id })
        assertEquals(10L, r[0].size)
        assertEquals(mapOf("path" to "x/a"), r[0].props)
        val first = server.takeRequest()
        val q1 = decodedQuery(first.path!!)
        assertTrue(q1, q1.contains("appProperties has { key='path' and value='x/a' } and trashed = false"))
        assertTrue(q1, q1.contains("pageSize=1000"))
        assertTrue(q1, q1.contains("nextPageToken,files(id,name,size,appProperties)"))
        assertFalse(q1, q1.contains("pageToken=T2"))
        val second = server.takeRequest()
        assertTrue(decodedQuery(second.path!!).contains("pageToken=T2"))
    }

    @Test fun smallUploadIsMultipartWithAppProperties() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, -1, -2)
        val f = tmp.newFile("book.epub").apply { writeBytes(payload) }
        val path = "Реинкарнация безработного/Том 06 - Детство.epub"
        server.enqueue(json("""{"id":"U1","name":"book.epub","size":"7","appProperties":{"path":"x"}}"""))
        val r = drive.upload("P", "book.epub", f, mapOf("path" to path), null)
        assertEquals("U1", r.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/upload/drive/v3/files", req.path!!.substringBefore('?'))
        assertTrue(req.path!!.contains("uploadType=multipart"))
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        val ct = req.getHeader("Content-Type")!!
        assertTrue(ct, ct.startsWith("multipart/related; boundary="))
        val boundary = ct.substringAfter("boundary=")
        val bytes = req.body.readByteArray()
        val text = String(bytes, Charsets.ISO_8859_1) // 1:1 byte/char mapping, safe for offsets
        assertTrue(text.endsWith("--$boundary--\r\n") || text.endsWith("--$boundary--"))
        val metaStart = text.indexOf('{')
        val metaEnd = text.indexOf("\r\n--$boundary", metaStart)
        val meta = JSONObject(String(bytes, metaStart, metaEnd - metaStart, Charsets.UTF_8))
        assertEquals("book.epub", meta.getString("name"))
        assertEquals("P", meta.getJSONArray("parents").getString(0))
        assertEquals(path, meta.getJSONObject("appProperties").getString("path"))
        val mediaStart = text.indexOf("\r\n\r\n", metaEnd) + 4
        assertEquals(payload.toList(), bytes.copyOfRange(mediaStart, mediaStart + payload.size).toList())
        assertEquals("\r\n--$boundary--", text.substring(mediaStart + payload.size).trimEnd('\r', '\n'))
    }

    @Test fun resumableUploadContinuesFromStoredOffset() {
        val size = mb5 + 1000
        val f = sparse("big.bin", size)
        val store = FakeResume().apply { put("P|big.bin", server.url("/session/S1").toString(), mb5) }
        server.enqueue(json("""{"id":"BIG","name":"big.bin","size":"$size"}"""))
        val r = drive.upload("P", "big.bin", f, mapOf("path" to "big"), store)
        assertEquals("BIG", r.id)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/session/S1", put.path)
        assertEquals("bytes $mb5-${size - 1}/$size", put.getHeader("Content-Range"))
        assertEquals(size - mb5, put.bodySize)
        assertNull(store.get("P|big.bin"))
    }

    @Test fun resumableUploadStartsSessionAndKeepsItOnFailure() {
        val size = mb5 + 10
        val f = sparse("big2.bin", size)
        val store = FakeResume()
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/S2").toString()))
        server.enqueue(MockResponse().setResponseCode(503))
        try { drive.upload("P", "big2.bin", f, mapOf("path" to "p"), store); fail() } catch (e: DriveException) {
            assertEquals(503, e.code)
        }
        val init = server.takeRequest()
        assertEquals("POST", init.method)
        assertTrue(init.path!!.contains("uploadType=resumable"))
        assertEquals("$size", init.getHeader("X-Upload-Content-Length"))
        assertEquals(server.url("/session/S2").toString(), store.get("P|big2.bin")?.first)
    }

    @Test fun resumableUploadContinuesAfter308() {
        val size = mb5 + 10
        val f = sparse("big3.bin", size)
        val store = FakeResume()
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/S3").toString()))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${mb5 - 1}"))
        server.enqueue(json("""{"id":"B3","name":"big3.bin","size":"$size"}"""))
        assertEquals("B3", drive.upload("P", "big3.bin", f, emptyMap(), store).id)
        server.takeRequest()
        val p1 = server.takeRequest()
        assertEquals("bytes 0-${mb5 - 1}/$size", p1.getHeader("Content-Range"))
        val p2 = server.takeRequest()
        assertEquals("bytes $mb5-${size - 1}/$size", p2.getHeader("Content-Range"))
        assertNull(store.get("P|big3.bin"))
    }

    @Test fun unauthorizedThrowsAuthException() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad\"}"))
        try { drive.list("P"); fail() } catch (e: DriveAuthException) { /* expected */ }
    }

    @Test fun tokenNeverAppearsInErrors() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Bearer $token boom"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("Bearer $token"))
        try { drive.delete("X"); fail() } catch (e: DriveException) {
            assertEquals(500, e.code)
            assertFalse(e.message!!.contains(token))
        }
        try { drive.delete("X"); fail() } catch (e: DriveAuthException) {
            assertFalse(e.message!!.contains(token))
        }
    }

    @Test fun downloadWritesBytesAndDeleteSendsDelete() {
        server.enqueue(MockResponse().setBody("hello"))
        val out = tmp.newFile("out.bin")
        drive.download("ID9", out)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/drive/v3/files/ID9?alt=media", get.path)
        assertEquals("hello", out.readText())
        server.enqueue(MockResponse().setResponseCode(204))
        drive.delete("ID9")
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/drive/v3/files/ID9", del.path)
    }

    private class FakeResume : ResumeStore {
        private val m = HashMap<String, Pair<String, Long>>()
        override fun get(key: String) = m[key]
        override fun put(key: String, url: String, offset: Long) { m[key] = url to offset }
        override fun clear(key: String) { m.remove(key) }
    }
}
