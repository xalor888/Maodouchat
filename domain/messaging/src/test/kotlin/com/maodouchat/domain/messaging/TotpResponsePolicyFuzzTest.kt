// TotpResponsePolicy (141st block). Oracle is a hand-written mini
// org.json model probed against real org.json 20240303 behaviour.
// A hand-written TotpResponsePolicyTest covers six fixed cases.
package com.maodouchat.domain.messaging

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- mini org.json model (probed, org.json 20240303) ----------

private class Json141Error : Exception()

private sealed interface JVal
private data class JStr(val s: String) : JVal
private data class JNum(val text: String, val jsonText: String) : JVal
private data class JBool(val b: Boolean) : JVal
private object JNull : JVal
private class JObj(val map: LinkedHashMap<String, JVal>) : JVal
private class JArr(val list: ArrayList<JVal>) : JVal

private class Json141Parser(private val s: String) {
    private var i = 0
    // org.json JSONTokener.eof: set when next() reads NUL (0) or hits end
    // of input; cleared by back(). A bare word terminated by NUL consumes
    // the NUL (no back-up); an object ',' after such a value throws
    // "must end with '}'" while the flag is still set.
    private var eof = false

    fun parseTopObject(): JObj {
        skipWs()
        if (i >= s.length || s[i] != '{') throw Json141Error()
        val v = parseObject()
        return v
    }

    private fun skipWs() {
        // org.json JSONTokener.nextClean: skips 0x01-0x20, stops on NUL (0).
        while (i < s.length && s[i].code in 1..0x20) i++
    }

    private fun parseValue(): JVal {
        skipWs()
        if (i >= s.length) throw Json141Error()
        val c = s[i]
        return when {
            c == '"' || c == '\'' -> JStr(parseString(c))
            c == '{' -> parseObject()
            c == '[' -> parseArray()
            c == '}' || c == ']' || c == ',' || c == ':' || c.code == 0 -> throw Json141Error()
            else -> stringToValue(parseBareWord())
        }
    }

    private fun parseString(quote: Char): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw Json141Error()
            val c = s[i]
            if (c.code == 0 || c == '\n' || c == '\r') throw Json141Error()
            if (c == quote) {
                i++
                return sb.toString()
            }
            if (c == '\\') {
                i++
                if (i >= s.length) throw Json141Error()
                when (val e = s[i]) {
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '/' -> sb.append('/')
                    '\\' -> sb.append('\\')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) throw Json141Error()
                        var code = 0
                        for (k in 1..4) {
                            val h = s[i + k]
                            val d = when (h) {
                                in '0'..'9' -> h - '0'
                                in 'a'..'f' -> h - 'a' + 10
                                in 'A'..'F' -> h - 'A' + 10
                                else -> throw Json141Error()
                            }
                            code = code * 16 + d
                        }
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> throw Json141Error()
                }
                i++
            } else {
                sb.append(c)
                i++
            }
        }
    }

    private fun parseBareWord(): String {
        val start = i
        while (i < s.length) {
            val c = s[i]
            if (c.code < 0x20) break
            if (c in ",:]}/\\\"[{;=#") break
            i++
        }
        val word = trimJava(s.substring(start, i))
        // nextSimpleValue backs up over a normal terminator (clearing
        // eof), but a NUL/EOF terminator is consumed with eof set.
        if (i < s.length && s[i].code == 0) {
            i++
            eof = true
        } else if (i >= s.length) {
            eof = true
        } else {
            eof = false
        }
        return word
    }

    private fun parseObject(): JObj {
        i++
        val map = LinkedHashMap<String, JVal>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return JObj(map)
        }
        while (true) {
            skipWs()
            if (i >= s.length) throw Json141Error()
            val key = if (s[i] == '"' || s[i] == '\'') parseString(s[i]) else {
                // Unquoted keys go through nextSimpleValue, not nextValue:
                // a '{'/'[' key is an empty word ("Missing value"), never
                // a nested container.
                val word = parseBareWord()
                if (word.isEmpty()) throw Json141Error()
                jValToKeyString(stringToValue(word))
            }
            if (map.containsKey(key)) throw Json141Error()
            skipWs()
            if (i >= s.length || s[i] != ':') throw Json141Error()
            i++
            val value = parseValue()
            map[key] = value
            skipWs()
            if (i >= s.length) throw Json141Error()
            when (s[i]) {
                ',', ';' -> {
                    i++
                    skipWs()
                    if (i < s.length && s[i] == '}') {
                        i++
                        return JObj(map)
                    }
                    if (eof) throw Json141Error()
                    eof = false
                }
                '}' -> {
                    i++
                    return JObj(map)
                }
                else -> throw Json141Error()
            }
        }
    }

    private fun parseArray(): JArr {
        i++
        val list = ArrayList<JVal>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return JArr(list)
        }
        while (true) {
            skipWs()
            if (i >= s.length) throw Json141Error()
            if (s[i] == ',') {
                list.add(JNull)
                i++
                skipWs()
                if (i < s.length && s[i] == ']') {
                    i++
                    return JArr(list)
                }
                continue
            }
            if (s[i] == ']') {
                i++
                return JArr(list)
            }
            eof = false
            list.add(parseValue())
            skipWs()
            if (i >= s.length) throw Json141Error()
            when (s[i]) {
                ',' -> {
                    i++
                    continue
                }
                ']' -> {
                    i++
                    return JArr(list)
                }
                else -> throw Json141Error()
            }
        }
    }
}

private fun trimJava(s: String): String {
    var a = 0
    var b = s.length
    while (a < b && s[a].code <= 0x20) a++
    while (b > a && s[b - 1].code <= 0x20) b--
    return s.substring(a, b)
}

private fun stringToValue(word: String): JVal {
    if (word.equals("true", ignoreCase = true)) return JBool(true)
    if (word.equals("false", ignoreCase = true)) return JBool(false)
    if (word.equals("null", ignoreCase = true)) return JNull
    if (word.isNotEmpty() && (word[0].isDigit() || word[0] == '-')) return numberOrString(word)
    return JStr(word)
}

private fun numberToString141(s: String): String {
    if ('.' in s && 'e' !in s && 'E' !in s) return s.trimEnd('0').trimEnd('.')
    return s
}

private fun numberOrString(word: String): JVal {
    if (word == "-0") return JNum("-0.0", "-0")
    if (word.any { it == '.' || it == 'e' || it == 'E' }) {
        return try {
            val bd = BigDecimal(word)
            if (bd.signum() == 0 && word.startsWith('-')) JNum("-0.0", "-0")
            else JNum(bd.toString(), numberToString141(bd.toString()))
        } catch (e: NumberFormatException) {
            JStr(word)
        }
    }
    if (word.length > 1 && (word[0] == '0' || (word[0] == '-' && word[1] == '0'))) return JStr(word)
    return try {
        val t = word.toLong().toString()
        JNum(t, t)
    } catch (e: NumberFormatException) {
        try {
            val t = BigInteger(word).toString()
            JNum(t, t)
        } catch (e2: NumberFormatException) {
            JStr(word)
        }
    }
}

private fun jValToKeyString(v: JVal): String = when (v) {
    is JStr -> v.s
    is JNum -> v.text
    is JBool -> if (v.b) "true" else "false"
    JNull -> "null"
    is JObj -> jToString(v)
    is JArr -> jToString(v)
}

private fun jQuote(s: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            // org.json JSONObject.quote: \uXXXX for < 0x20, 0x80-0x9F,
            // 0x2000-0x20FF; '/' stays raw.
            else -> if (c.code < 0x20 || c.code in 0x80 until 0xA0 || c.code in 0x2000 until 0x2100) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

// org.json JSONObject keeps entries in a java.util.HashMap: toString walks
// bucket order (capacity 16, x0.75 resize), insertion order inside a bucket.
private fun hashMapOrder141(entries: List<Pair<String, JVal>>): List<Pair<String, JVal>> {
    var cap = 16
    while (entries.size > cap * 3 / 4) cap *= 2
    fun bucket(key: String): Int {
        val h = key.hashCode()
        return (h xor (h ushr 16)) and (cap - 1)
    }
    return entries.withIndex().sortedWith(compareBy({ bucket(it.value.first) }, { it.index })).map { it.value }
}

// Wire codec for the differential harness: lowercase hex of UTF-16 units,
// same as the probe's encStr (kept local so the test file compiles alone).
private fun hexEnc141(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun jToString(v: JVal): String = when (v) {
    is JStr -> jQuote(v.s)
    is JNum -> v.jsonText
    is JBool -> if (v.b) "true" else "false"
    JNull -> "null"
    is JObj -> "{" + hashMapOrder141(v.map.entries.map { it.key to it.value }).joinToString(",") { (k, x) -> jQuote(k) + ":" + jToString(x) } + "}"
    is JArr -> "[" + v.list.joinToString(",") { jToString(it) } + "]"
}

private fun jElemOptString(v: JVal): String = when (v) {
    is JStr -> v.s
    JNull -> ""
    is JNum -> v.text
    is JBool -> if (v.b) "true" else "false"
    is JObj -> jToString(v)
    is JArr -> jToString(v)
}

private fun jOptString(obj: JObj, key: String): String {
    val v = obj.map[key] ?: return ""
    return jElemOptString(v)
}

private fun jOptBoolean(obj: JObj, key: String, default: Boolean): Boolean {
    val v = obj.map[key] ?: return default
    if (v is JBool) return v.b
    if (v is JStr) {
        if (v.s.equals("true", ignoreCase = true)) return true
        if (v.s.equals("false", ignoreCase = true)) return false
    }
    return default
}

private fun jOptArray(obj: JObj, key: String): JArr? {
    val v = obj.map[key] ?: return null
    return v as? JArr
}

private fun jGetString(arr: JArr, index: Int): String {
    val v = arr.list[index]
    if (v !is JStr) throw Json141Error()
    return v.s
}

// ---------- policy oracles ----------

private fun enabledOracle(body: String): Boolean = try {
    jOptBoolean(Json141Parser(body).parseTopObject(), "enabled", false)
} catch (e: Exception) {
    false
}

private fun setupOracle(body: String): String = try {
    val obj = Json141Parser(body).parseTopObject()
    val secret = jOptString(obj, "secret")
    if (secret.isBlank()) throw IllegalArgumentException("Missing secret in TOTP response")
    val url = jOptString(obj, "otpauthUrl").takeIf { it.isNotBlank() }
    val codes = mutableListOf<String>()
    val arr = jOptArray(obj, "backupCodes")
    if (arr != null) {
        for (i in arr.list.indices) codes.add(jGetString(arr, i))
    }
    "OK|" + hexEnc141(secret) + "|" + (if (url == null) "N" else "S" + hexEnc141(url)) + "|" + codes.joinToString(",") { hexEnc141(it) }
} catch (e: Json141Error) {
    "THROW|JSONException"
} catch (e: IllegalArgumentException) {
    "THROW|IllegalArgumentException"
}

private fun codesOracle(body: String): List<String> {
    val obj = try {
        Json141Parser(body).parseTopObject()
    } catch (e: Exception) {
        return emptyList()
    }
    val arr = jOptArray(obj, "backupCodes") ?: return emptyList()
    return arr.list.map { jElemOptString(it) }.filter { it.isNotBlank() }
}

private fun setupProduction(body: String): String = try {
    val r = TotpResponsePolicy.parseTotpSetupResponse(body)
    "OK|" + hexEnc141(r.secret) + "|" + (if (r.otpauthUrl == null) "N" else "S" + hexEnc141(r.otpauthUrl)) + "|" + r.recoveryCodes.joinToString(",") { hexEnc141(it) }
} catch (e: Exception) {
    "THROW|" + e.javaClass.simpleName
}

// ---------- generators (mirrored by Oracle141 GEN, seed 141001) ----------

private val TT_KEY_POOL = arrayOf("enabled", "secret", "otpauthUrl", "backupCodes", "other", "x")
private val TT_STRING_POOL = arrayOf(
    "", " ", "ABC", "abc", "a b", "true", "false", "null", "otpauth://totp/x",
    "a,b", "x\ty", "é😀", "a\"b", "a\\b", "a/b", "  padded  ", "\u00A0", "0", "123",
    "a\u0001b", "line\nbreak", "c1", "c2", "a'b", "a:b", "a=b", "a#b", "a;b",
)
private val TT_NUMBER_POOL = arrayOf(
    "0", "1", "-1", "100", "1.0", "0.5", "1e3", "1E-3", "-0", "0.0", "-0.0",
    "1e100", "9223372036854775807", "9223372036854775808", "007", "00", "01",
    "NaN", "Infinity", "-Infinity", "1.5", "0.30000000000000004", "5.", ".5",
)

private fun randomText141(rnd: Random, maxLen: Int = 12): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, maxLen + 1)
    repeat(len) {
        when (rnd.nextInt(6)) {
            0 -> sb.append(('a' + rnd.nextInt(26)))
            1 -> sb.append(('0' + rnd.nextInt(10)))
            2 -> sb.append(" \t\n\r\"'\\/{}[],:=#".get(rnd.nextInt(16)))
            3 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            4 -> sb.appendCodePoint(rnd.nextInt(0x10000, 0x1FFFF))
            else -> sb.append(TT_STRING_POOL[rnd.nextInt(TT_STRING_POOL.size)])
        }
    }
    return sb.toString()
}

private fun jsonEscape141(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

private fun genJsonValue141(rnd: Random, depth: Int): String = when (rnd.nextInt(6)) {
    0 -> "\"" + jsonEscape141(
        when (rnd.nextInt(3)) {
            0 -> TT_STRING_POOL[rnd.nextInt(TT_STRING_POOL.size)]
            1 -> randomText141(rnd, 8)
            else -> ""
        }
    ) + "\""
    1 -> TT_NUMBER_POOL[rnd.nextInt(TT_NUMBER_POOL.size)]
    2 -> if (rnd.nextBoolean()) "true" else "false"
    3 -> "null"
    4 -> if (depth >= 2) "\"x\"" else "[" + (0 until rnd.nextInt(4)).joinToString(",") { genJsonValue141(rnd, depth + 1) } + "]"
    else -> if (depth >= 2) "\"x\"" else "{" + (0 until rnd.nextInt(3)).joinToString(",") {
        "\"" + TT_KEY_POOL[rnd.nextInt(TT_KEY_POOL.size)] + "\":" + genJsonValue141(rnd, depth + 1)
    } + "}"
}

private fun genTtBody(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> {
        val keys = TT_KEY_POOL.toList().shuffled(rnd).take(rnd.nextInt(1, 5))
        "{" + keys.joinToString(",") { "\"" + it + "\":" + genJsonValue141(rnd, 0) } + "}"
    }
    1 -> "\"" + jsonEscape141(randomText141(rnd, 10)) + "\""
    2 -> randomText141(rnd, 24)
    else -> {
        val base = "{\"secret\":\"ABC\",\"backupCodes\":[\"a\",\"b\"]}"
        when (rnd.nextInt(6)) {
            0 -> base.replace("\"", "'")
            1 -> base.replace("\"secret\"", "secret")
            2 -> base.replace("}", ",}")
            3 -> base.replace("\"secret\":\"ABC\",", "\"secret\":\"ABC\",\"secret\":\"DEF\",")
            4 -> base.replace(",", "")
            else -> base.substring(0, rnd.nextInt(1, base.length))
        }
    }
}

private fun genTtLine(rnd: Random): String {
    val body = genTtBody(rnd)
    return "tt:" + body
}

class TotpResponsePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val bodies = listOf(
            "{\"enabled\":true}",
            "{\"enabled\":false}",
            "{\"enabled\":\"true\"}",
            "{\"enabled\":\"TRUE\"}",
            "{\"enabled\":1}",
            "{\"enabled\":null}",
            "{\"other\":1}",
            "not json",
            "",
            "{'enabled':true}",
            "{enabled:true}",
            "{\"enabled\":true,}",
            "{\"enabled\":true} trailing",
            "{\"enabled\":true,\"enabled\":false}",
            "{\"enabled\":tru}",
            "{\"secret\":\"ABC\"}",
            "{\"secret\":\"ABC\",\"otpauthUrl\":\"otpauth://x\",\"backupCodes\":[\"a\",\"b\"]}",
            "{\"secret\":\"  \"}",
            "{\"secret\":123}",
            "{\"secret\":true}",
            "{\"secret\":null}",
            "{\"secret\":[\"a\"]}",
            "{\"secret\":\"ABC\",\"backupCodes\":[1,true,null]}",
            "{\"secret\":\"ABC\",\"backupCodes\":\"abc\"}",
            "{\"secret\":\"ABC\",\"backupCodes\":[123]}",
            "{\"secret\":\"ABC\",\"otpauthUrl\":123}",
            "{\"secret\":\"ABC\",\"otpauthUrl\":\"\"}",
            "{\"secret\":\"ABC\",\"backupCodes\":[\"a\",\"\",\"b\"]}",
            "{\"secret\":1e3}",
            "{\"secret\":0.5}",
            "{\"secret\":-0}",
            "{\"secret\":007}",
            "{\"secret\":NaN}",
            "{\"secret\":{\"b\":1,\"a\":2}}",
            "{\"secret\":abc}",
            "{\"secret\":TRUE}",
            "{\"secret\":\"a\\qb\"}",
            "{\"secret\":\"a\\/b\"}",
            "{\"secret\":\"a\\u0041b\"}",
            "{\"secret\":\"ABC\",\"backupCodes\":[1,2,]}",
            "{\"secret\":\"ABC\",\"backupCodes\":[\"a\",\"b\",]}",
            "{\"secret\":\"ABC\" \"backupCodes\":[]}",
            "{\"secret\":\"ABC\",}",
            "{\"secret\":a/b}",
            "{\"secret\":a b}",
            "{\"secret\":}",
            "{\"secret\":\"ABC\",\"backupCodes\":[,]}",
            "{\"secret\":\"ABC\",\"backupCodes\":[\"a\" \"b\"]}",
            "{\"backupCodes\":[\"a\",\"\",\"b\"]}",
            "{\"backupCodes\":[1,true,null]}",
            "{\"backupCodes\":[null]}",
            "{\"backupCodes\":[[\"x\"]]}",
            "{\"backupCodes\":[{\"x\":1}]}",
            "{\"backupCodes\":[1.5]}",
            "{\"backupCodes\":[\" a \"]}",
            "{\"backupCodes\":[\"\u00A0\"]}",
            "{\"backupCodes\":[\"\\u0000\"]}",
        )
        for (body in bodies) {
            assertEquals(enabledOracle(body), TotpResponsePolicy.parseTotpEnabled(body), "enabled body=$body")
            assertEquals(setupOracle(body), setupProduction(body), "setup body=$body")
            assertEquals(codesOracle(body), TotpResponsePolicy.parseBackupCodes(body), "codes body=$body")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(141001)
        repeat(1500) { i ->
            val body = genTtBody(rnd)
            assertEquals(enabledOracle(body), TotpResponsePolicy.parseTotpEnabled(body), "enabled iter=$i body=$body")
            assertEquals(setupOracle(body), setupProduction(body), "setup iter=$i body=$body")
            assertEquals(codesOracle(body), TotpResponsePolicy.parseBackupCodes(body), "codes iter=$i body=$body")
        }
    }

    @Test
    fun sweepExhaustive() {
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa0\u0000"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        val literals = listOf(
            "{\"enabled\":true}", "{\"secret\":\"ABC\"}", "{\"backupCodes\":[\"a\"]}",
            "true", "false", "null", "NaN", "Infinity", "1e3", "007", "-0",
        )
        for (base in literals) {
            for (body in editsOf(base)) {
                assertEquals(enabledOracle(body), TotpResponsePolicy.parseTotpEnabled(body), "enabled sweep body=$body")
                assertEquals(setupOracle(body), setupProduction(body), "setup sweep body=$body")
                assertEquals(codesOracle(body), TotpResponsePolicy.parseBackupCodes(body), "codes sweep body=$body")
            }
        }
        for (num in TT_NUMBER_POOL) {
            val body = "{\"secret\":$num}"
            assertEquals(setupOracle(body), setupProduction(body), "number sweep body=$body")
            val body2 = "{\"backupCodes\":[$num]}"
            assertEquals(codesOracle(body2), TotpResponsePolicy.parseBackupCodes(body2), "codes number sweep body=$body2")
        }
        for (code in 0x00..0xFF) {
            val body = String(Character.toChars(code))
            assertEquals(enabledOracle(body), TotpResponsePolicy.parseTotpEnabled(body), "single-unit body=$code")
            assertEquals(setupOracle(body), setupProduction(body), "single-unit setup body=$code")
            assertEquals(codesOracle(body), TotpResponsePolicy.parseBackupCodes(body), "single-unit codes body=$code")
        }
    }
}
