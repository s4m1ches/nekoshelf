package app.meebook.launcher.data.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LuaTableTest {
    @Suppress("UNCHECKED_CAST")
    private fun table(text: String) = LuaTable.parse(text) as Map<Any, Any?>

    private fun assertFails(text: String) {
        try {
            LuaTable.parse(text)
            fail("expected LuaParseException for: $text")
        } catch (e: LuaParseException) {
            // expected
        }
    }

    @Test
    fun parsesNestedTables() {
        val t = table("return { [\"a\"] = 1, [\"b\"] = { [1] = \"x\", [2] = \"y\" }, }")
        assertEquals(1L, t["a"])
        assertEquals(mapOf<Any, Any?>(1L to "x", 2L to "y"), t["b"])
    }

    @Test
    fun parsesEscapes() {
        assertEquals("say \"hi\"\n", table("return { [\"s\"] = \"say \\\"hi\\\"\\n\" }")["s"])
        assertEquals("\\", table("return { [\"s\"] = \"\\\\\" }")["s"])
        assertEquals("a\tb\rc", table("return { [\"s\"] = \"a\\tb\\rc\" }")["s"])
        assertEquals("Aé", table("return { [\"s\"] = \"\\65\\195\\169\" }")["s"])
        assertEquals("a\nb", table("return { [\"s\"] = \"a\\\nb\" }")["s"])
    }

    @Test
    fun parsesNumbers() {
        val t = table("return { [\"d\"] = 0.012571783330747, [\"n\"] = -1, [\"e\"] = 1e-3, [\"E\"] = 2.5E+2 }")
        assertEquals(0.012571783330747, t["d"])
        assertEquals(-1L, t["n"])
        assertEquals(1e-3, t["e"])
        assertEquals(250.0, t["E"])
    }

    @Test
    fun parsesBooleansNilAndBareKeys() {
        val t = table("return { [\"t\"] = true, [\"f\"] = false, [\"n\"] = nil, bare = 5 }")
        assertEquals(true, t["t"])
        assertEquals(false, t["f"])
        assertNull(t["n"])
        assertEquals(5L, t["bare"])
    }

    @Test
    fun acceptsLeadingCommentsAndNoReturn() {
        assertEquals(1L, table("-- /path/x.lua\nreturn {\n  [\"a\"] = 1,\n}\n")["a"])
        assertEquals(1L, table("{ [\"a\"] = 1 }")["a"])
        assertEquals(emptyMap<Any, Any?>(), table("return {}"))
    }

    @Test
    fun parsesFixture() {
        val text = javaClass.classLoader!!.getResourceAsStream("koreader/metadata_skorb.lua")!!
            .readBytes().toString(Charsets.UTF_8)
        val t = table(text)
        assertEquals(0.012571783330747, t["percent_finished"])
        @Suppress("UNCHECKED_CAST")
        val props = t["doc_props"] as Map<Any, Any?>
        assertEquals("Скорбь любви меж Цзин и Вэй", props["title"])
    }

    @Test
    fun malformedInputThrowsLuaParseException() {
        assertFails("return { [\"a\"] = ")
        assertFails("")
        assertFails("return")
        assertFails("return { [\"a\"] = \"unterminated }")
        assertFails("return { [\"a\"] = 1 } trailing")
        assertFails("return { [\"a\"] 1 }")
        assertFails("return { [\"a\"] = @ }")
        assertFails("return { [\"a\"] = \"\\q\" }")
        assertFails("return { [\"a\"] = 1.2.3 }")
        assertFails("return " + "{".repeat(100000))
    }

    @Test
    fun resultIsTableTypeCheck() {
        assertTrue(LuaTable.parse("return {}") is Map<*, *>)
    }
}
