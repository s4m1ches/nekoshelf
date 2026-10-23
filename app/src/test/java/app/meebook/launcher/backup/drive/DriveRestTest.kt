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

    private fun key(f: File) = "P|${f.name}|${f.length()}|${f.lastModified()}"
    private fun statusRange(end: Long) = MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${end - 1}")

    @Test fun resumableUploadContinuesFromServerOffset() {
        val size = mb5 + 1000
        val f = sparse("big.bin", size)
        // stored offset (100) disagrees with what the server really has (5 MB): the server wins
        val store = FakeResume().apply { put(key(f), server.url("/session/S1").toString(), 100) }
        server.enqueue(statusRange(mb5))
        server.enqueue(json("""{"id":"BIG","name":"big.bin","size":"$size"}"""))
        assertEquals("BIG", drive.upload("P", "big.bin", f, mapOf("path" to "big"), store).id)
        val status = server.takeRequest()
        assertEquals("PUT", status.method)
        assertEquals("/session/S1", status.path)
        assertEquals("bytes */$size", status.getHeader("Content-Range"))
        assertEquals(0L, status.bodySize)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/session/S1", put.path)
        assertEquals("bytes $mb5-${size - 1}/$size", put.getHeader("Content-Range"))
        assertEquals(size - mb5, put.bodySize)
        assertNull(store.get(key(f)))
    }

    @Test fun resumeStatusAlreadyCompleteReturnsFile() {
        val size = mb5 + 1
        val f = sparse("done.bin", size)
        val store = FakeResume().apply { put(key(f), server.url("/session/D").toString(), 0) }
        server.enqueue(json("""{"id":"DONE","name":"done.bin","size":"$size"}"""))
        assertEquals("DONE", drive.upload("P", "done.bin", f, emptyMap(), store).id)
        assertEquals(1, server.requestCount)
        assertNull(store.get(key(f)))
    }

    @Test fun changedFileIgnoresStoredSession() {
        val size = mb5 + 10
        val f = sparse("chg.bin", size)
        val store = FakeResume().apply { put("P|chg.bin|$size|${f.lastModified() - 5000}", server.url("/session/OLD").toString(), mb5) }
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/NEW").toString()))
        server.enqueue(json("""{"id":"C1","name":"chg.bin","size":"$size"}"""))
        assertEquals("C1", drive.upload("P", "chg.bin", f, emptyMap(), store).id)
        assertTrue(server.takeRequest().path!!.contains("uploadType=resumable"))
        val put = server.takeRequest()
        assertEquals("/session/NEW", put.path)
        assertEquals("bytes 0-${mb5 - 1}/$size", put.getHeader("Content-Range"))
    }

    @Test fun badRequestOnResumeRestartsOnceThenSucceeds() {
        val size = mb5 + 10
        val f = sparse("rst.bin", size)
        val store = FakeResume().apply { put(key(f), server.url("/session/STALE").toString(), 0) }
        server.enqueue(MockResponse().setResponseCode(400)) // status query on stale session
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/FRESH").toString()))
        server.enqueue(statusRange(mb5))
        server.enqueue(json("""{"id":"R1","name":"rst.bin","size":"$size"}"""))
        assertEquals("R1", drive.upload("P", "rst.bin", f, emptyMap(), store).id)
        assertEquals("/session/STALE", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.contains("uploadType=resumable"))
        assertEquals("/session/FRESH", server.takeRequest().path)
        assertEquals("/session/FRESH", server.takeRequest().path)
    }

    @Test fun chunkRejectedTwiceFailsAfterOneRestart() {
        val size = mb5 + 10
        val f = sparse("rst2.bin", size)
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/A").toString()))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/B").toString()))
        server.enqueue(MockResponse().setResponseCode(416))
        try { drive.upload("P", "rst2.bin", f, emptyMap(), FakeResume()); fail() } catch (e: DriveException) {
            assertEquals(416, e.code)
        }
        assertEquals(4, server.requestCount)
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
        assertEquals(server.url("/session/S2").toString(), store.get(key(f))?.first)
    }

    @Test fun resumableUploadContinuesAfter308() {
        val size = mb5 + 10
        val f = sparse("big3.bin", size)
        val store = FakeResume()
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/S3").toString()))
        server.enqueue(statusRange(mb5))
        server.enqueue(json("""{"id":"B3","name":"big3.bin","size":"$size"}"""))
        assertEquals("B3", drive.upload("P", "big3.bin", f, emptyMap(), store).id)
        server.takeRequest()
        assertEquals("bytes 0-${mb5 - 1}/$size", server.takeRequest().getHeader("Content-Range"))
        assertEquals("bytes $mb5-${size - 1}/$size", server.takeRequest().getHeader("Content-Range"))
        assertNull(store.get(key(f)))
    }

    @Test fun exactMultipleOfChunkFinishes() {
        val size = 2 * mb5
        val f = sparse("two.bin", size)
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/X").toString()))
        server.enqueue(statusRange(mb5))
        server.enqueue(json("""{"id":"X2","name":"two.bin","size":"$size"}"""))
        assertEquals("X2", drive.upload("P", "two.bin", f, emptyMap(), null).id)
        server.takeRequest()
        assertEquals("bytes 0-${mb5 - 1}/$size", server.takeRequest().getHeader("Content-Range"))
        val last = server.takeRequest()
        assertEquals("bytes $mb5-${size - 1}/$size", last.getHeader("Content-Range"))
        assertEquals(mb5, last.bodySize)
    }

    @Test fun unexpected2xxOnChunkThrowsInsteadOfLooping() {
        val f = sparse("odd.bin", mb5 + 10)
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/Y").toString()))
        server.enqueue(MockResponse().setResponseCode(202))
        try { drive.upload("P", "odd.bin", f, emptyMap(), null); fail() } catch (e: DriveException) {
            assertEquals(202, e.code)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun nonAdvancing308Throws() {
        val f = sparse("stuck.bin", mb5 + 10)
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/Z").toString()))
        server.enqueue(MockResponse().setResponseCode(308)) // no Range header: nothing committed
        try { drive.upload("P", "stuck.bin", f, emptyMap(), null); fail() } catch (e: DriveException) {
            assertEquals(308, e.code)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun statusWith308AndNoRangeRestartsFromZero() {
        val size = mb5 + 10
        val f = sparse("zero.bin", size)
        val store = FakeResume().apply { put(key(f), server.url("/session/Q").toString(), mb5) }
        server.enqueue(MockResponse().setResponseCode(308)) // status: nothing committed
        server.enqueue(statusRange(mb5))
        server.enqueue(json("""{"id":"Q1","name":"zero.bin","size":"$size"}"""))
        assertEquals("Q1", drive.upload("P", "zero.bin", f, emptyMap(), store).id)
        server.takeRequest()
        assertEquals("bytes 0-${mb5 - 1}/$size", server.takeRequest().getHeader("Content-Range"))
        assertEquals("bytes $mb5-${size - 1}/$size", server.takeRequest().getHeader("Content-Range"))
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
