package app.nekoshelf.launcher.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MangaDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun freeNameIsKeptAsIs() {
        val dir = tmp.newFolder("Download")
        assertEquals(File(dir, "manga-2026-10-23.tachibk"), freeDownloadTarget(dir, "manga-2026-10-23.tachibk"))
    }

    @Test fun takenNamesGetANumberAndNothingIsOverwritten() {
        val dir = tmp.newFolder("Download")
        File(dir, "manga-2026-10-23.tachibk").writeText("first")
        assertEquals("manga-2026-10-23 (2).tachibk", freeDownloadTarget(dir, "manga-2026-10-23.tachibk").name)
        File(dir, "manga-2026-10-23 (2).tachibk").writeText("second")
        assertEquals("manga-2026-10-23 (3).tachibk", freeDownloadTarget(dir, "manga-2026-10-23.tachibk").name)
    }

    @Test fun saveCopiesIntoAFreeNameAndLeavesNoTemp() {
        val dir = tmp.newFolder("Download")
        File(dir, "manga-2026-10-23.tachibk").writeText("older save")
        val saved = saveIntoFolder(dir, "manga-2026-10-23.tachibk") { it.writeText("backup bytes") }
        assertEquals("manga-2026-10-23 (2).tachibk", saved.name)
        assertEquals("backup bytes", saved.readText())
        assertEquals("older save", File(dir, "manga-2026-10-23.tachibk").readText())
        assertEquals(setOf("manga-2026-10-23.tachibk", "manga-2026-10-23 (2).tachibk"), dir.list()!!.toSet())
    }

    @Test fun failedDownloadLeavesNothing() {
        val dir = tmp.newFolder("Download")
        runCatching { saveIntoFolder(dir, "manga-2026-10-23.tachibk") { it.writeText("half"); error("network") } }
        assertFalse(File(dir, "manga-2026-10-23.tachibk").exists())
        assertEquals(0, dir.list()!!.size)
    }
}
