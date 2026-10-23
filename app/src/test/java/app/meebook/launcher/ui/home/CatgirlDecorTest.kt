package app.meebook.launcher.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatgirlDecorTest {
    @Test
    fun svgAspectFromViewBox() {
        assertEquals(64f / 106f, svgAspect("""<!-- bookshelf:overhang=18 --><svg viewBox="0 0 64 106">""")!!, 1e-4f)
        assertEquals(96f / 106f, svgAspect("""<svg xmlns="x" viewBox="0,0,96,106" >""")!!, 1e-4f)
        assertNull(svgAspect("""<svg width="64">"""))
        assertNull(svgAspect("""<svg viewBox="0 0 64 0">"""))
    }

    @Test
    fun pngAspectFromIhdr() {
        // 8-byte signature, IHDR length + type, then width 552 and height 1347 (big-endian).
        val head = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0x02, 0x28, 0, 0, 0x05, 0x43,
        )
        assertEquals(552f / 1347f, pngAspect(head)!!, 1e-4f)
        assertNull(pngAspect(ByteArray(24)))
        assertNull(pngAspect(head.copyOf(10)))
    }

    @Test
    fun placementTallFigureIsHeightBoundAndStartsAtTop() {
        // Header 512 dp wide, stage 45 dp, frame 118 dp; aspect 0.4 (standing figure).
        val p = decorPlacement(width = 512.dp, stage = 45.dp, frame = 118.dp, aspect = 0.4f)
        assertEquals((45 + 118 * DECOR_FRAME_OVERLAP).toFloat(), p.height.value, 0.01f)
        assertEquals(p.height.value * 0.4f, p.width.value, 0.01f)
        assertEquals(0f, p.top.value, 0.01f)
    }

    @Test
    fun placementWideFigureIsWidthBoundAndCutByTheEdgeInHalf() {
        val p = decorPlacement(width = 512.dp, stage = 45.dp, frame = 118.dp, aspect = 1.09f)
        assertEquals(512f * DECOR_MAX_SHARE, p.width.value, 0.01f)
        assertEquals(p.width.value / 1.09f, p.height.value, 0.01f)
        assertEquals(45f - p.height.value / 2, p.top.value, 0.01f) // the frame edge crosses its middle
        assertEquals(512f * DECOR_CENTER - p.width.value / 2, p.left.value, 0.01f)
    }
}
