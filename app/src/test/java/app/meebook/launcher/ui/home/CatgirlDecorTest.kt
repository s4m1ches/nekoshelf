package app.meebook.launcher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CatgirlDecorTest {
    @Test
    fun overhangIsAShareOfTheViewBoxHeight() {
        val svg = """<!-- bookshelf:overhang=18 -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 106">"""
        assertEquals(18f / 106f, parseOverhang(svg), 1e-4f)
    }

    @Test
    fun noMarkerOrNoViewBoxMeansNoOverhang() {
        assertEquals(0f, parseOverhang("""<svg viewBox="0 0 64 106">"""), 0f)
        assertEquals(0f, parseOverhang("""<!-- bookshelf:overhang=0 --><svg viewBox="0 0 96 106">"""), 0f)
        assertEquals(0f, parseOverhang("""<!-- bookshelf:overhang=18 --><svg width="64">"""), 0f)
    }

    @Test
    fun absurdOverhangIsClamped() {
        assertEquals(0.5f, parseOverhang("""<!-- bookshelf:overhang=500 --><svg viewBox="0 0 64 106">"""), 0f)
    }
}
