package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ---------- oracle ----------

// Lenient JSON value tree: every primitive collapses to its content
// string (numbers keep their raw literal, null reads as "null"),
// which is all errorMessage ever observes.
internal sealed interface JsonValue162 {
    data class Str(val content: String) : JsonValue162
    data class Obj(val map: LinkedHashMap<String, JsonValue162>) : JsonValue162
    data class Arr(val items: List<JsonValue162>) : JsonValue162
}

internal class LenientJsonParseException162(message: String) : Exception(message)

// Small lenient JSON parser, same modeled range as the Python model:
// strict JSON plus unquoted keys/values and literal control chars in
// quoted strings. Trailing commas, comments and single-quoted strings
// are NOT accepted (probed: the real parser rejects all three).
internal class LenientJsonParser162(private val text: String) {
    private var i = 0

    fun parseTopLevel(): JsonValue162 {
        val v = parseValue()
        skipWs()
        if (i != text.length) throw LenientJsonParseException162("trailing")
        return v
    }

    private fun skipWs() {
        while (i < text.length &&
            (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')
        ) i++
    }

    private fun parseValue(): JsonValue162 {
        skipWs()
        if (i >= text.length) throw LenientJsonParseException162("eof")
        return when (text[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue162.Str(parseQuoted())
            else -> JsonValue162.Str(parseToken())
        }
    }

    private fun parseObject(): JsonValue162.Obj {
        i++
        val map = LinkedHashMap<String, JsonValue162>()
        skipWs()
        if (i < text.length && text[i] == '}') {
            i++
            return JsonValue162.Obj(map)
        }
        while (true) {
            skipWs()
            val key = parseKey()
            skipWs()
            if (i >= text.length || text[i] != ':') throw LenientJsonParseException162("colon")
            i++
            map[key] = parseValue()
            skipWs()
            if (i >= text.length) throw LenientJsonParseException162("eof")
            when (text[i]) {
                ',' -> {
                    i++
                    continue
                }
                '}' -> {
                    i++
                    return JsonValue162.Obj(map)
                }
                else -> throw LenientJsonParseException162("comma")
            }
        }
    }

    private fun parseArray(): JsonValue162.Arr {
        i++
        val items = ArrayList<JsonValue162>()
        skipWs()
        if (i < text.length && text[i] == ']') {
            i++
            return JsonValue162.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWs()
            if (i >= text.length) throw LenientJsonParseException162("eof")
            when (text[i]) {
                ',' -> {
                    i++
                    continue
                }
                ']' -> {
                    i++
                    return JsonValue162.Arr(items)
                }
                else -> throw LenientJsonParseException162("comma")
            }
        }
    }

    private fun parseKey(): String {
        skipWs()
        if (i >= text.length) throw LenientJsonParseException162("eof")
        return if (text[i] == '"') parseQuoted() else parseToken()
    }

    // An unquoted token runs until a delimiter: quote, backslash,
    // braces, brackets, comma, colon, whitespace, or any control char below 0x20.
    private fun isTokenEnd(c: Char): Boolean =
        c == '"' || c == '\\' || c == '{' || c == '}' || c == '[' || c == ']' ||
            c == ',' || c == ':' || c == ' ' || c.code < 0x20

    private fun parseToken(): String {
        val start = i
        while (i < text.length && !isTokenEnd(text[i])) i++
        if (i == start) throw LenientJsonParseException162("empty token")
        return text.substring(start, i)
    }

    private fun parseQuoted(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= text.length) throw LenientJsonParseException162("unterminated")
            val c = text[i++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (i >= text.length) throw LenientJsonParseException162("escape")
                when (val e = text[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > text.length) throw LenientJsonParseException162("unicode")
                        var v = 0
                        repeat(4) {
                            val h = text[i++]
                            val d = when (h) {
                                in '0'..'9' -> h - '0'
                                in 'a'..'f' -> h - 'a' + 10
                                in 'A'..'F' -> h - 'A' + 10
                                else -> throw LenientJsonParseException162("hex")
                            }
                            v = v * 16 + d
                        }
                        sb.append(v.toChar())
                    }
                    else -> throw LenientJsonParseException162("escape")
                }
            } else {
                sb.append(c)
            }
        }
    }
}

// A non-primitive error/message value throws, like jsonPrimitive
// does, so the whole lookup fails instead of falling through.
internal fun stringOrNull162(obj: Map<String, JsonValue162>, key: String): String? {
    val v = obj[key] ?: return null
    if (v !is JsonValue162.Str) throw LenientJsonParseException162("not primitive")
    return v.content.takeIf { it.isNotBlank() }
}

internal fun errorMessageOracle162(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
    return try {
        val parsed = LenientJsonParser162(trimmed).parseTopLevel()
        val obj = (parsed as? JsonValue162.Obj)?.map ?: return null
        stringOrNull162(obj, "error") ?: stringOrNull162(obj, "message")
    } catch (e: Exception) {
        null
    }
}

internal fun serverErrorTextOracle162(failureMessage: String): String? =
    errorMessageOracle162(failureMessage.substringAfter(':', failureMessage))

// ---------- generators ----------

internal val GRP_WORDS162 = listOf(
    "", "e", "m", "oops", "hello", "a b", "a:b", "a/b", "x", "  ", " ",
    "\u00a0", "\u0085", "\u200b", "你好", "true", "null", "NaN", "42", "1e2",
    "a\"b", "a\\b", "a\nb", "a\tb", "\uD83D\uDE00", "e\u0000x",
)

internal val GRP_TOKEN_ALPHABET162 =
    "abcxyzABCXYZ019_-./$@#?*=+%!~`|;()<>'\u00e9\u4e2d\u007f\u0085\u00a0\u200b\uFEFF"

internal val GRP_STR_ALPHABET162 =
    "ab \t\n\u00a0\u0085\u200b\u2003\u3000\u00e9\u4e2d\uD83D\uDE00x:/\\\",'{}[]"

internal fun grpJsonEscape162(s: String): String {
    // Layer-1 escaper for the wire JSON: every control char becomes \uXXXX.
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun grpBodyEscape162(s: String, rawControls: Boolean): String {
    // Layer-2 escaper for quoted strings inside the generated body:
    // rawControls keeps control chars literal (lenient strings allow them).
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20 && !rawControls) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun randomGrpString162(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(GRP_STR_ALPHABET162[rnd.nextInt(GRP_STR_ALPHABET162.length)]) }
    return sb.toString()
}

internal fun randomGrpWord162(rnd: Random): String =
    if (rnd.nextInt(10) < 4) GRP_WORDS162[rnd.nextInt(GRP_WORDS162.size)]
    else randomGrpString162(rnd)

internal fun randomGrpQuoted162(rnd: Random): String {
    val raw = rnd.nextInt(10) == 0
    return "\"" + grpBodyEscape162(randomGrpWord162(rnd), raw) + "\""
}

internal fun randomGrpToken162(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 1
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(GRP_TOKEN_ALPHABET162[rnd.nextInt(GRP_TOKEN_ALPHABET162.length)]) }
    return sb.toString()
}

internal val GRP_NUMBER_TOKENS162 = listOf(
    "0", "5", "-1", "1e2", "1.50", "007", "+5", ".5", "5.", "1e",
    "NaN", "Infinity", "-Infinity", "0x10", "1_000", "01", "00",
    "true", "false", "null", "3.14", "1E+5", "-.5",
)

internal fun randomGrpValue162(rnd: Random, depth: Int): String = when (rnd.nextInt(10)) {
    0, 1, 2, 3 -> randomGrpQuoted162(rnd)
    4, 5 -> randomGrpToken162(rnd)
    6 -> GRP_NUMBER_TOKENS162[rnd.nextInt(GRP_NUMBER_TOKENS162.size)]
    7 -> if (depth < 3) randomGrpObject162(rnd, depth + 1) else randomGrpQuoted162(rnd)
    8 -> if (depth < 3) randomGrpArray162(rnd, depth + 1) else randomGrpQuoted162(rnd)
    else -> randomGrpQuoted162(rnd)
}

internal fun randomGrpKey162(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\"error\""
    1 -> "\"message\""
    2 -> "\"other\""
    3 -> "\"\""
    4 -> randomGrpQuoted162(rnd)
    5 -> "error"
    6 -> "message"
    else -> randomGrpToken162(rnd)
}

internal fun randomGrpObject162(rnd: Random, depth: Int): String {
    val members = rnd.nextInt(5)
    val sb = StringBuilder()
    sb.append("{")
    for (i in 0 until members) {
        if (i > 0) sb.append(", ")
        sb.append(randomGrpKey162(rnd))
        if (rnd.nextInt(20) == 0) sb.append(" ") else sb.append(": ")
        sb.append(randomGrpValue162(rnd, depth))
    }
    if (members > 0 && rnd.nextInt(20) == 0) sb.append(",")
    sb.append("}")
    return sb.toString()
}

internal fun randomGrpArray162(rnd: Random, depth: Int): String {
    val items = rnd.nextInt(4)
    val sb = StringBuilder()
    sb.append("[")
    for (i in 0 until items) {
        if (i > 0) sb.append(", ")
        sb.append(randomGrpValue162(rnd, depth))
    }
    if (items > 0 && rnd.nextInt(20) == 0) sb.append(",")
    sb.append("]")
    return sb.toString()
}

internal fun randomGrpBody162(rnd: Random): String = when (rnd.nextInt(10)) {
    0, 1, 2, 3, 4, 5 -> randomGrpObject162(rnd, 0)
    6 -> when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> "not json"
        3 -> "[1,2]"
        4 -> "\"str\""
        5 -> "42"
        6 -> "{"
        7 -> "}"
        8 -> "{}"
        else -> "{error:}"
    }
    7 -> randomGrpObject162(rnd, 0) + " x"
    8 -> randomGrpObject162(rnd, 0) + "}"
    else -> when (rnd.nextInt(10)) {
        0 -> "{\"error\":\"e\",}"
        1 -> "{\"error\":\"e\" \"message\":\"m\"}"
        2 -> "{\"error\" \"e\"}"
        3 -> "{\"error\":}"
        4 -> "{'error':'e'}"
        5 -> "{\"error\": \"e\" /* c */}"
        6 -> "{\"error\":\"\\q\"}"
        7 -> "{\"error\":\"\\u00g0\"}"
        8 -> "{\"error\":'single'}"
        else -> "{error: hello world}"
    }
}

internal fun randomGrpFailure162(rnd: Random): String {
    val body = randomGrpBody162(rnd)
    return when (rnd.nextInt(10)) {
        0 -> body
        1 -> "HTTP 500: $body"
        2 -> "Error: $body"
        3 -> "x:$body"
        4 -> "a: b: $body"
        5 -> randomGrpToken162(rnd) + ": " + body
        6 -> ": $body"
        7 -> "HTTP 500: $body"
        else -> "x: $body"
    }
}

internal data class GrpCase162(
    val value: String,
)

internal fun randomGrpCase162(rnd: Random): GrpCase162 = GrpCase162(randomGrpFailure162(rnd))

internal fun grpWireJson162(case: GrpCase162): String =
    "{\"value\":\"" + grpJsonEscape162(case.value) + "\"}"

// Every lenient corner probed against real production this round;
// each entry is checked against the oracle, which predicts it.
internal val GRP_PINNED162 = listOf(
    "HTTP 500: {\"error\":\"服务端忙\"}",
    "Error: {\"message\":\"oops\"}",
    "boom",
    "",
    "500: not json",
    "x: {}",
    "a: b: {\"error\":\"e\"}",
    "x: {\"error\":\"e\"}",
    "x: {\"message\":\"m\"}",
    "x: {\"error\":\"e\",\"message\":\"m\"}",
    "x: {\"error\":\"\",\"message\":\"m\"}",
    "x: {\"error\":\" \",\"message\":\"m\"}",
    "x: {\"error\":\"\",\"message\":\"\"}",
    "x: {\"error\":5}",
    "x: {\"error\":true}",
    "x: {\"error\":false}",
    "x: {\"error\":null}",
    "x: {\"error\":1e2}",
    "x: {\"error\":1.50}",
    "x: {\"error\":007}",
    "x: {\"error\":+5}",
    "x: {\"error\":.5}",
    "x: {\"error\":5.}",
    "x: {\"error\":1e}",
    "x: {\"error\":NaN}",
    "x: {\"error\":Infinity}",
    "x: {\"error\":-Infinity}",
    "x: {\"error\":0x10}",
    "x: {\"error\":1_000}",
    "x: {\"error\":0}",
    "x: {\"error\":-1}",
    "x: {\"error\":3.14}",
    "x: {\"error\":1E+5}",
    "x: {\"error\":-.5}",
    "x: {\"error\":+.5}",
    "x: {\"error\":{\"x\":1},\"message\":\"m\"}",
    "x: {\"error\":[\"a\"],\"message\":\"m\"}",
    "x: {\"message\":{\"x\":1}}",
    "x: {\"message\":[1,2]}",
    "x: {\"error\":\"a\",\"error\":\"b\"}",
    "x: {\"error\":\"e\",\"message\":\"m\",\"error\":\"e2\"}",
    "x: {\"Error\":\"e\"}",
    "x: {\"error\":\"e\"} ",
    "x: {\"error\":\"e\"} x",
    "x: {\"error\":\"e\"}}",
    "x: {\"error\":\"e\"} {\"message\":\"m\"}",
    "x: {",
    "x: {\"error\":}",
    "x: {\"error\" \"e\"}",
    "x: {\"error\":\"e\" \"message\":\"m\"}",
    "x: {\"error\":\"e\",}",
    "x: {\"error\":\"e\", \"message\":\"m\",}",
    "x: {\"error\":\"e\",,}",
    "x: {,}",
    "x: {\"error\":\"e\", \"message\":\"m\"} ",
    "x: {\"a\":[1,2,],\"error\":\"e\"}",
    "x: {\"error\":[\"a\",],\"message\":\"m\"}",
    "x: {\"error\":\"a\nb\"}",
    "x: {\"error\":\"a\tb\"}",
    "x: {\"error\":\"a\u0000b\"}",
    "x: {\"error\":\"a\u0001b\"}",
    "x: {\"error\":\"a\u001fb\"}",
    "x: {\"error\":\"a\u000bb\"}",
    "x: {\"error\":\"a\u000cb\"}",
    "x: {\"error\":\"a\rb\"}",
    "x: {\"error\":\"\\u4e2d\"}",
    "x: {\"error\":\"\\ud800\"}",
    "x: {\"error\":\"\\ud83d\\ude00\"}",
    "x: {\"err\\u006fr\":\"e\"}",
    "x: {\"error\":\"a\\/b\"}",
    "x: {\"error\":\"\\b\"}",
    "x: {\"error\":\"\\f\"}",
    "x: {\"error\":\"\\n\"}",
    "x: {\"error\":\"\\r\"}",
    "x: {\"error\":\"\\t\"}",
    "x: {\"error\":\"\\\\\"}",
    "x: {\"error\":\"\\\"\"}",
    "x: {\"error\":\"\\u00E9\"}",
    "x: {\"error\":\"\\u00e9\"}",
    "x: {\"error\":\"\\q\"}",
    "x: {\"error\":\"\\u00g0\"}",
    "x: {\"error\":\"\\u\"}",
    "x: {\"error\":\"\\'\"}",
    "x: {\"error\":\"\\0\"}",
    "x: {\"error\": \"e\" /* c */}",
    "x: {error:\"e\"}",
    "x: {error:\"e\",message:\"m\"}",
    "x: {'error':'e'}",
    "x: {'error': 'e', 'message': 'm'}",
    "x: {\"error\":'single'}",
    "x: {'error':\"double\"}",
    "x: {error: oops}",
    "x: {error: hello world}",
    "x: {error: a/b}",
    "x: {error: a-b}",
    "x: {error: a_b}",
    "x: {error: a.b}",
    "x: {error: a:b}",
    "x: {error: a,b}",
    "x: {error: 42}",
    "x: {error: true}",
    "x: {error: false}",
    "x: {error: null}",
    "x: {error: NaN}",
    "x: {error: True}",
    "x: {error: TRUE}",
    "x: {error: http://x}",
    "x: {error: a'b}",
    "x: {error: 'e'}",
    "x: {error: \"e\"}",
    "x: {error: a\\b}",
    "x: {\"error\": \"\\u00a0\"}",
    "x: {\"error\": \"\\u0085\"}",
    "x: {\"error\": \"\\u200b\"}",
    "x: {\"error\": \"e\\u0000\"}",
    "x: {\"error\":\"e\"}\t",
    "x: {\"error\":\"e\"}\n",
    "x: {\r\n\"error\":\r\n\"e\"\r\n}",
    "x: {\"error\":\"a{b}c:d\"}",
    "x: {\"er:ror\":\"e\"}",
    "x: {\"error \":\"e\"}",
    "x: {\"\":\"e\"}",
    "x: {\"a\":{\"b\":\"c\"},\"error\":\"e\"}",
    "x: {\"a\":[{\"b\":1}],\"error\":\"e\"}",
    "x: {\"a\":[b],\"error\":\"e\"}",
    "x: {\"a\":{b:1},\"error\":\"e\"}",
    "x: [1,2]",
    "x: \"hello\"",
    "x: 42",
    "x: {",
    "x: ",
    "x: {error:}",
    ": {\"error\":\"e\"}",
    "x:{\"error\":\"e\"}",
    "x: {\"error\":\"e\"} y",
    ":: {\"error\":\"e\"}",
    "  {\"error\":\"e\"}  ",
    "\u00a0{\"error\":\"e\"}",
    "x: {\"error\":\"\uD83D\uDE00\"}",
    "x: {\"error\":\"\uD800\"}",
    "x: {\"error\":\"e\",\"message\":\"m\"}",
    "x: {\"message\": \"m\", \"error\": \"e\"}",
    "x: {\"error\": \"\", \"message\": {\"x\":1}}",
    "x: {\"error\": [], \"message\": \"m\"}",
    "x: {\"error\": \"e\", \"message\": \"m\"} ",
    "x: {\"error\":\"e\",\"message\":\"m\",\"error\":\"e2\"}",
    "x: {\"error\":\"q\",error:\"u\"}",
    "x: {error:\"a\",\"error\":\"b\"}",
    "x: {\"error\":\"a\",error:\"b\"}",
)

class GroupPlayErrorPolicyFuzzTest {

    private fun check(value: String) {
        assertEquals(
            serverErrorTextOracle162(value),
            GroupPlayErrorPolicy.serverErrorText(value),
        )
    }

    @Test
    fun pinnedBoundaries() {
        assertEquals("服务端忙", GroupPlayErrorPolicy.serverErrorText("HTTP 500: {\"error\":\"服务端忙\"}"))
        assertEquals("oops", GroupPlayErrorPolicy.serverErrorText("Error: {\"message\":\"oops\"}"))
        assertNull(GroupPlayErrorPolicy.serverErrorText("boom"))
        assertNull(GroupPlayErrorPolicy.serverErrorText(""))
        assertNull(GroupPlayErrorPolicy.serverErrorText("500: not json"))
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {}"))
        assertNull(GroupPlayErrorPolicy.serverErrorText("a: b: {\"error\":\"e\"}"))
        // A non-primitive error value fails the whole lookup, no fallback.
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {\"error\":{\"x\":1},\"message\":\"m\"}"))
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {\"error\":[\"a\"],\"message\":\"m\"}"))
        // Null reads as the literal "null".
        assertEquals("null", GroupPlayErrorPolicy.serverErrorText("x: {\"error\":null}"))
        // Trailing comma / comments / single quotes are not lenient here.
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {\"error\":\"e\",}"))
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {'error':'e'}"))
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {\"error\": \"e\" /* c */}"))
        // Unquoted keys and values are lenient; single quotes are token chars.
        assertEquals("e", GroupPlayErrorPolicy.serverErrorText("x: {error:\"e\"}"))
        assertEquals("'single'", GroupPlayErrorPolicy.serverErrorText("x: {\"error\":'single'}"))
        for (value in GRP_PINNED162) {
            check(value)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(162003)
        repeat(2000) {
            check(randomGrpCase162(rnd).value)
        }
    }

    @Test
    fun sweepExhaustive() {
        for (value in GRP_PINNED162) {
            check(value)
        }
        // error/message/other keys against every value shape.
        val keys = listOf("\"error\"", "\"message\"", "\"other\"", "error", "message", "\"\"")
        val values = listOf(
            "\"e\"", "\"\"", "\" \"", "5", "true", "false", "null", "1e2",
            "007", "NaN", "Infinity", "oops", "{}", "[]", "{\"x\":1}", "[\"a\"]",
        )
        for (key in keys) {
            for (value in values) {
                check("x: {$key:$value}")
                check("x: {$key:$value,\"message\":\"m\"}")
                check("x: {\"error\":\"\",$key:$value}")
            }
        }
        // Every UTF-16 unit inside a quoted error string and as an unquoted token.
        for (u in 0..0xFFFF) {
            val c = u.toChar().toString()
            check("x: {\"error\":\"a" + c + "b\"}")
            check("x: {error: a" + c + "}")
        }
        // Prefix shapes against a few bodies.
        val bodies = listOf("{\"error\":\"e\"}", "{\"message\":\"m\"}", "not json", "{}", "{\"error\":\"e\"} x")
        val prefixes = listOf("", "x:", "x: ", "HTTP 500: ", "a: b: ", ":", ":: ", "e r r: ")
        for (prefix in prefixes) {
            for (body in bodies) {
                check(prefix + body)
            }
        }
    }
}
