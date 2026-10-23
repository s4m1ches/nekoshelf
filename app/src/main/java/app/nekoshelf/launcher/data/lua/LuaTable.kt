package app.nekoshelf.launcher.data.lua

class LuaParseException(message: String) : Exception(message)

/**
 * Parser for the subset of Lua that KOReader's `dump()` serializer emits: an optional
 * `-- comment` header, optional `return`, then a table constructor.
 *
 * Tables become `Map<Any, Any?>` with `String` or `Long` keys; values are `Map`, `String`,
 * `Long`, `Double`, `Boolean` or `null`. Any malformed input throws [LuaParseException].
 * Decimal escapes `\ddd` are treated as bytes (Lua semantics); runs of bytes >= 128 are
 * decoded as UTF-8.
 */
object LuaTable {
    private const val MAX_DEPTH = 200

    fun parse(text: String): Any? {
        try {
            return Parser(text).parseDocument()
        } catch (e: LuaParseException) {
            throw e
        } catch (e: Exception) {
            throw LuaParseException("Malformed Lua: ${e.message}")
        } catch (e: StackOverflowError) {
            throw LuaParseException("Lua nesting too deep")
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            if (s.startsWith("﻿")) i = 1
            skipWs()
            if (matchWord("return")) skipWs()
            val v = parseValue(0)
            skipWs()
            if (i < s.length) fail("trailing content")
            return v
        }

        private fun fail(msg: String): Nothing = throw LuaParseException("$msg at offset $i")

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) {
                    i++
                } else if (c == '-' && s.startsWith("--", i)) {
                    if (s.startsWith("--[[", i)) {
                        val end = s.indexOf("]]", i + 4)
                        if (end < 0) fail("unterminated block comment")
                        i = end + 2
                    } else {
                        while (i < s.length && s[i] != '\n') i++
                    }
                } else break
            }
        }

        private fun isIdentChar(c: Char) = c == '_' || c.isLetterOrDigit()

        private fun peekWord(w: String) =
            s.startsWith(w, i) && (i + w.length >= s.length || !isIdentChar(s[i + w.length]))

        private fun matchWord(w: String): Boolean {
            if (!peekWord(w)) return false
            i += w.length
            return true
        }

        private fun parseValue(depth: Int): Any? {
            skipWs()
            if (i >= s.length) fail("unexpected end of input")
            val c = s[i]
            return when {
                c == '{' -> parseTable(depth + 1)
                c == '"' || c == '\'' -> parseString()
                c == '-' || c == '+' || c == '.' || c.isDigit() -> parseNumber()
                matchWord("true") -> true
                matchWord("false") -> false
                matchWord("nil") -> null
                else -> fail("unexpected '$c'")
            }
        }

        private fun parseTable(depth: Int): Map<Any, Any?> {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            i++ // {
            val map = LinkedHashMap<Any, Any?>()
            var arrayIndex = 1L
            while (true) {
                skipWs()
                if (i >= s.length) fail("unterminated table")
                if (s[i] == '}') {
                    i++
                    return map
                }
                if (s[i] == '[') {
                    i++
                    val key = parseKey()
                    skipWs()
                    if (i >= s.length || s[i] != ']') fail("expected ']'")
                    i++
                    skipWs()
                    if (i >= s.length || s[i] != '=') fail("expected '='")
                    i++
                    putValue(map, key, parseValue(depth))
                } else if (s[i] == '_' || s[i].isLetter()) {
                    val start = i
                    while (i < s.length && isIdentChar(s[i])) i++
                    val name = s.substring(start, i)
                    skipWs()
                    if (i < s.length && s[i] == '=' && !s.startsWith("==", i)) {
                        i++
                        putValue(map, name, parseValue(depth))
                    } else {
                        // positional value that happens to be true/false/nil
                        i = start
                        putValue(map, arrayIndex++, parseValue(depth))
                    }
                } else {
                    putValue(map, arrayIndex++, parseValue(depth))
                }
                skipWs()
                if (i >= s.length) fail("unterminated table")
                when (s[i]) {
                    ',', ';' -> i++
                    '}' -> {}
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun putValue(map: MutableMap<Any, Any?>, key: Any, value: Any?) {
            // Lua semantics: assigning nil means no entry.
            if (value == null) map.remove(key) else map[key] = value
        }

        private fun parseKey(): Any {
            skipWs()
            if (i >= s.length) fail("unexpected end of input")
            val c = s[i]
            return when {
                c == '"' || c == '\'' -> parseString()
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    val n = parseNumber()
                    if (n is Double && n == Math.floor(n) && Math.abs(n) < 9.0e15) n.toLong() else n
                }
                matchWord("true") -> true
                matchWord("false") -> false
                else -> fail("invalid table key")
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (s[i] == '-' || s[i] == '+') i++
            if (s.startsWith("0x", i) || s.startsWith("0X", i)) {
                i += 2
                val hs = i
                while (i < s.length && (s[i].isDigit() || s[i].lowercaseChar() in 'a'..'f')) i++
                if (i == hs) fail("bad hex number")
                val v = s.substring(hs, i).toLongOrNull(16) ?: fail("bad hex number")
                return if (s[start] == '-') -v else v
            }
            var isFloat = false
            val ds = i
            while (i < s.length && s[i].isDigit()) i++
            val intDigits = i - ds
            var fracDigits = 0
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                val fs = i
                while (i < s.length && s[i].isDigit()) i++
                fracDigits = i - fs
            }
            if (intDigits == 0 && fracDigits == 0) fail("bad number")
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                val es = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == es) fail("bad exponent")
            }
            if (i < s.length && isIdentChar(s[i])) fail("bad number")
            val tok = s.substring(start, i)
            if (!isFloat) {
                tok.toLongOrNull()?.let { return it }
            }
            return tok.toDoubleOrNull() ?: fail("bad number '$tok'")
        }

        private fun parseString(): String {
            val quote = s[i++]
            val sb = StringBuilder()
            val pending = java.io.ByteArrayOutputStream()

            fun flush() {
                if (pending.size() > 0) {
                    sb.append(String(pending.toByteArray(), Charsets.UTF_8))
                    pending.reset()
                }
            }

            fun emit(ch: Char) {
                flush()
                sb.append(ch)
            }

            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == quote -> {
                        flush()
                        return sb.toString()
                    }
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated string")
                        val e = s[i++]
                        when (e) {
                            'n' -> emit('\n')
                            't' -> emit('\t')
                            'r' -> emit('\r')
                            'a' -> emit('\u0007')
                            'b' -> emit('\b')
                            'f' -> emit('\u000C')
                            'v' -> emit('\u000B')
                            '\\' -> emit('\\')
                            '"' -> emit('"')
                            '\'' -> emit('\'')
                            '\n' -> emit('\n')
                            '\r' -> {
                                if (i < s.length && s[i] == '\n') i++
                                emit('\n')
                            }
                            in '0'..'9' -> {
                                var n = e - '0'
                                var cnt = 1
                                while (cnt < 3 && i < s.length && s[i] in '0'..'9') {
                                    n = n * 10 + (s[i] - '0')
                                    i++
                                    cnt++
                                }
                                if (n > 255) fail("decimal escape too large")
                                if (n < 128) emit(n.toChar()) else pending.write(n)
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c == '\n' -> fail("newline in string")
                    else -> emit(c)
                }
            }
        }
    }
}
