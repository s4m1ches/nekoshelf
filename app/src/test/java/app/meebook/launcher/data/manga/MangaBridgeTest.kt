package app.meebook.launcher.data.manga

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.meebook.launcher.domain.DayStat
import app.meebook.launcher.domain.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate

private const val AUTHORITY = "app.komikku.meebook.bridge"

/** Test double for the «Манга» provider; behaviour is switched through the companion. */
class FakeMangaProvider : ContentProvider() {
    companion object {
        var failWith: RuntimeException? = null
        var failFirstCalls = 0
        var nullCursor = false
        var coverFile: File? = null
        var calls = 0
        /** When set, every query waits for it first: a slow (cold) «Манга» provider. */
        @Volatile var gate: java.util.concurrent.CountDownLatch? = null
        fun reset() {
            failWith = null; failFirstCalls = 0; nullCursor = false; coverFile = null; calls = 0; gate = null
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        gate?.await(30, java.util.concurrent.TimeUnit.SECONDS)
        calls++
        if (calls <= failFirstCalls) throw IllegalStateException("cold start")
        failWith?.let { throw it }
        if (nullCursor) return null
        return when (uri.pathSegments.firstOrNull()) {
            "library" -> MatrixCursor(
                arrayOf(
                    "manga_id", "title", "source_name", "date_added", "last_read_at", "chapters_total",
                    "chapters_read", "next_chapter_id", "next_chapter_name", "cover_version",
                ),
            ).apply {
                addRow(arrayOf<Any?>(1L, "Berserk", "Src", 1000L, 5000L, 100, 40, 41L, "Ch. 41", 777L))
                addRow(arrayOf<Any?>(2L, "Vagabond", "Src", 2000L, 0L, 10, 10, null, null, 0L))
            }
            "stats_daily" -> MatrixCursor(arrayOf("day", "pages", "seconds", "backfilled")).apply {
                addRow(arrayOf<Any?>("2026-10-23", 12, 340, 0))
                addRow(arrayOf<Any?>("2026-10-22", 0, 900, 1))
            }
            "totals" -> MatrixCursor(arrayOf("pages_total", "seconds_total")).apply {
                addRow(arrayOf<Any?>(5_000_000_000L, 7L))
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = coverFile ?: throw FileNotFoundException("no cover")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaBridgeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var bridge: MangaBridge

    @Before fun setUp() {
        FakeMangaProvider.reset()
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            packageName = "app.komikku.meebook"
            name = FakeMangaProvider::class.java.name
            exported = true
        }
        Robolectric.buildContentProvider(FakeMangaProvider::class.java).create(info)
        bridge = MangaBridge(context, retryDelayMs = 0)
    }

    @Test fun libraryMapsRowsExactly() {
        assertEquals(
            listOf(
                Manga(1, "Berserk", 5000L, 100, 40, "Ch. 41", 1000L, 777L),
                Manga(2, "Vagabond", 0L, 10, 10, null, 2000L, 0L),
            ),
            bridge.library(),
        )
    }

    @Test fun nullNextChapterNameIsNull() {
        assertNull(bridge.library()[1].nextChapterName)
    }

    @Test fun securityExceptionYieldsEmptyLibrary() {
        FakeMangaProvider.failWith = SecurityException("denied")
        assertTrue(bridge.library().isEmpty())
    }

    @Test fun nullCursorYieldsEmptyAndNull() {
        FakeMangaProvider.nullCursor = true
        assertTrue(bridge.library().isEmpty())
        assertTrue(bridge.daily().isEmpty())
        assertEquals(0L to 0L, bridge.totals())
    }

    @Test fun retriesOnceOnColdStartFailure() {
        FakeMangaProvider.failFirstCalls = 1
        assertEquals(2, bridge.library().size)
        assertEquals(2, FakeMangaProvider.calls)
    }

    @Test fun givesUpAfterOneRetry() {
        FakeMangaProvider.failFirstCalls = 5
        assertTrue(bridge.library().isEmpty())
        assertEquals(2, FakeMangaProvider.calls)
    }

    @Test fun dailyParsesDays() {
        assertEquals(
            listOf(DayStat(LocalDate.of(2026, 10, 23), 12, 340), DayStat(LocalDate.of(2026, 10, 22), 0, 900)),
            bridge.daily(),
        )
        assertTrue(bridge.hasBackfilledDays())
    }

    @Test fun totalsReadsLongs() {
        assertEquals(5_000_000_000L to 7L, bridge.totals())
    }

    @Test fun coverStreamsFileOrNull() {
        assertNull(bridge.cover(1))
        val f = tmp.newFile("c.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        FakeMangaProvider.coverFile = f
        val s = bridge.cover(1)
        assertNotNull(s)
        assertEquals(3, s!!.use { it.readBytes().size })
    }

    @Test fun installedIgnoresThePermission() {
        assertFalse(bridge.installed())
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "app.komikku.meebook" })
        assertTrue(bridge.installed())
        assertFalse(bridge.available())
    }

    @Test fun availableNeedsPackageAndPermission() {
        assertFalse(bridge.available())
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "app.komikku.meebook" })
        assertFalse(bridge.available())
        shadowOf(context as android.app.Application).grantPermissions("app.komikku.meebook.permission.BRIDGE")
        assertTrue(bridge.available())
    }

    @Test fun registerObserverFiresOnNotify() {
        var n = 0
        val obs = bridge.registerObserver { n++ }
        context.contentResolver.notifyChange(Uri.parse("content://$AUTHORITY/"), null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, n)
        context.contentResolver.unregisterContentObserver(obs)
    }
}
