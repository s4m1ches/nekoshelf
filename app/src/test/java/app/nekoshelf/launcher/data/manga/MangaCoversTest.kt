package app.nekoshelf.launcher.data.manga

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class MangaCoversTest {
    @get:Rule val tmp = TemporaryFolder()

    private val calls = mutableListOf<Long>()
    private var image: ByteArray? = byteArrayOf(1, 2, 3)

    private fun covers(dir: File = tmp.root, ttl: Long = 60_000) = MangaCovers(dir, ttl) { id ->
        calls += id
        image?.let { ByteArrayInputStream(it) }
    }

    @Test
    fun hitIsCachedOnDiskAndNotFetchedAgain() {
        val c = covers()
        val f = c.cover(7, 100)
        assertNotNull(f)
        assertArrayEquals(byteArrayOf(1, 2, 3), f!!.readBytes())
        assertEquals(f, covers().cover(7, 100)) // a new instance (process restart) reuses the file
        assertEquals(listOf(7L), calls)
    }

    @Test
    fun newCoverVersionRefetchesAndDropsTheOldFile() {
        val c = covers()
        val old = c.cover(7, 100)!!
        image = byteArrayOf(9)
        val new = c.cover(7, 101)!!
        assertArrayEquals(byteArrayOf(9), new.readBytes())
        assertEquals(false, old.exists())
        assertEquals(listOf(7L, 7L), calls)
    }

    @Test
    fun missIsRememberedAcrossInstancesUntilTtl() {
        image = null
        assertNull(covers().cover(5, 1))
        assertNull(covers().cover(5, 1))
        assertEquals(listOf(5L), calls)
        // Expired marker: asked again, and this time the cover exists.
        File(tmp.root, "manga_covers/5_1.miss").setLastModified(System.currentTimeMillis() - 120_000)
        image = byteArrayOf(4)
        assertNotNull(covers().cover(5, 1))
        assertEquals(listOf(5L, 5L), calls)
        assertEquals(false, File(tmp.root, "manga_covers/5_1.miss").exists())
    }

    @Test
    fun emptyStreamIsAMiss() {
        image = ByteArray(0)
        assertNull(covers().cover(3, 1))
        assertNull(covers().cover(3, 1))
        assertEquals(listOf(3L), calls)
    }

    @Test
    fun coverVersionZeroMeansNoCoverAndIsNeverFetched() {
        // Contract: cover_version 0 = no cover. Asking anyway costs the bridge's 300 ms retry.
        assertNull(covers().cover(8, 0))
        assertNull(covers().cover(8, 0))
        assertEquals(emptyList<Long>(), calls)
        assertEquals(false, File(tmp.root, "manga_covers/8_0.miss").exists())
    }
}
