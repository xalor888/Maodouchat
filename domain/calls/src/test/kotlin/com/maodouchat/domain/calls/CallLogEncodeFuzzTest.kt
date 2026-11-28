package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr194l(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr194l(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

// org.json JSONObject.quote 的独立重实现：逐 UTF-16 码元转义。
private fun quoteOracle194l(s: String): String {
    val out = StringBuilder()
    out.append('"')
    var prev = 0
    for (ch in s) {
        val c = ch.code
        when (ch) {
            '\\', '"' -> out.append('\\').append(ch)
            '/' -> {
                if (prev == '<'.code) out.append('\\')
                out.append(ch)
            }
            '\b' -> out.append("\\b")
            '\t' -> out.append("\\t")
            '\n' -> out.append("\\n")
            '\u000C' -> out.append("\\f")
            '\r' -> out.append("\\r")
            else -> {
                if (c < 0x20 || (c in 0x80..0x9F) || (c in 0x2000..0x20FF)) {
                    out.append("\\u")
                    out.append(c.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        prev = c
    }
    out.append('"')
    return out.toString()
}

// encodeCallLogEntry 的独立重实现：键序是 org.json HashMap 的固定迭代序
//（dur, at, peer, name, id, video, state, dir, group），与插入序无关。
private fun encodeEntryOracle194l(e: CallLogEntry): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"dur\":").append(e.durationMs)
    sb.append(",\"at\":").append(e.startedAt)
    sb.append(",\"peer\":").append(quoteOracle194l(e.peerId))
    sb.append(",\"name\":").append(quoteOracle194l(e.peerName))
    sb.append(",\"id\":").append(quoteOracle194l(e.id))
    sb.append(",\"video\":").append(e.isVideo)
    sb.append(",\"state\":").append(quoteOracle194l(if (e.state == State.MISSED) "missed" else "answered"))
    sb.append(",\"dir\":").append(quoteOracle194l(if (e.direction == Direction.OUTGOING) "out" else "in"))
    sb.append(",\"group\":").append(e.isGroup)
    sb.append('}')
    return sb.toString()
}

private fun encodeLogOracle194l(entries: List<CallLogEntry>): String =
    entries.joinToString(",", "[", "]") { encodeEntryOracle194l(it) }

private val LOG_STRS194l = listOf(
    "", " ", "c1", "peer-1", "a/b", "a?b", "a%b", "a b", "\u00e9", "\u4e2d\u6587", "\ud83d\ude00",
    "x".repeat(80), "a\"b", "a'b", "a\nb", "a\tb", "a\bb", "a\u000cb", "a\rb", "a\u0001b", "a\u001fb",
    "a\u007fb", "a\u0085b", "a\u2028b", "a\u2000b", "a\u20ffb", "a\u2100b", "a\\b", "a/b", "a</b", "a<", "<",
    "0", "-1", "out", "in", "missed", "answered", "true", "null", "a\uFFFFb", "\uD800\uDC00"
)

private val LOG_RANDOM_CHARS194l = listOf(
    "a", "b", "c", "X", "Y", "Z", "0", "1", "9", " ", "_", "-", ".",
    "\"", "\\", "\n", "\t", "\u00e9", "\ud83d\ude00", "<", "/"
)

private fun randomLogStr194l(r: Random): String =
    if (r.nextInt(3) == 0) LOG_STRS194l[r.nextInt(LOG_STRS194l.size)]
    else (0 until r.nextInt(12)).joinToString("") { LOG_RANDOM_CHARS194l[r.nextInt(LOG_RANDOM_CHARS194l.size)] }

private val LOG_LONGS194l = listOf(
    0L, 1L, -1L, 60_000L, 3_600_000L, Long.MAX_VALUE, Long.MIN_VALUE,
    Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 123456789012345L, -123456789012345L
)

private fun randomLogEntry194l(r: Random): CallLogEntry = CallLogEntry(
    id = randomLogStr194l(r),
    peerId = randomLogStr194l(r),
    peerName = randomLogStr194l(r),
    isVideo = r.nextBoolean(),
    direction = if (r.nextBoolean()) Direction.OUTGOING else Direction.INCOMING,
    state = if (r.nextBoolean()) State.MISSED else State.ANSWERED,
    startedAt = if (r.nextInt(3) == 0) LOG_LONGS194l[r.nextInt(LOG_LONGS194l.size)] else r.nextLong(-1000, 2_000_000_000),
    durationMs = if (r.nextInt(3) == 0) LOG_LONGS194l[r.nextInt(LOG_LONGS194l.size)] else r.nextLong(-1000, 3_600_000),
    isGroup = r.nextBoolean()
)

private fun randomLog194l(r: Random): List<CallLogEntry> =
    List(r.nextInt(0, 6)) { randomLogEntry194l(r) }

private fun boundaryLog194l(r: Random): List<CallLogEntry> {
    val edge = LOG_STRS194l[r.nextInt(LOG_STRS194l.size)]
    val n = r.nextInt(0, 4)
    return List(n) {
        CallLogEntry(
            id = edge,
            peerId = edge,
            peerName = edge,
            isVideo = r.nextBoolean(),
            direction = Direction.OUTGOING,
            state = State.MISSED,
            startedAt = LOG_LONGS194l[r.nextInt(LOG_LONGS194l.size)],
            durationMs = LOG_LONGS194l[r.nextInt(LOG_LONGS194l.size)],
            isGroup = r.nextBoolean()
        )
    }
}

// ---------- tests ----------

class CallLogEncodeFuzzTest {

    @Test
    fun logDifferential194() {
        val r = Random(194003)
        repeat(300) {
            val entries = randomLog194l(r)
            assertEquals(encodeLogOracle194l(entries), encodeCallLog(entries))
        }
    }

    @Test
    fun logBoundaryDifferential194() {
        val r = Random(194004)
        repeat(300) {
            val entries = boundaryLog194l(r)
            assertEquals(encodeLogOracle194l(entries), encodeCallLog(entries))
        }
    }

    @Test
    fun logPinned194() {
        // 空表。
        assertEquals("[]", encodeCallLog(emptyList()))
        assertEquals("[]", encodeLogOracle194l(emptyList()))
        // 固定条目：键序与值格式逐字核对（实测 org.json 输出）。
        val one = CallLogEntry(
            id = "x", peerId = "p", peerName = "n", isVideo = true,
            direction = Direction.OUTGOING, state = State.MISSED,
            startedAt = -5L, durationMs = 123456789012345L, isGroup = false
        )
        val oneJson = "{\"dur\":123456789012345,\"at\":-5,\"peer\":\"p\",\"name\":\"n\",\"id\":\"x\",\"video\":true,\"state\":\"missed\",\"dir\":\"out\",\"group\":false}"
        assertEquals("[$oneJson]", encodeCallLog(listOf(one)))
        assertEquals("[$oneJson]", encodeLogOracle194l(listOf(one)))
        // 转义边缘：引号/反斜杠/换行照 org.json 规则转义，非 ASCII 原样输出。
        val esc = CallLogEntry(
            id = "a/b\"c\nd", peerId = "", peerName = "\u00e9\ud83d\ude00", isVideo = false,
            direction = Direction.INCOMING, state = State.ANSWERED,
            startedAt = 0L, durationMs = 0L, isGroup = true
        )
        val escJson = "{\"dur\":0,\"at\":0,\"peer\":\"\",\"name\":\"\u00e9\ud83d\ude00\",\"id\":\"a/b\\\"c\\nd\",\"video\":false,\"state\":\"answered\",\"dir\":\"in\",\"group\":true}"
        assertEquals("[$escJson]", encodeCallLog(listOf(esc)))
        assertEquals("[$escJson]", encodeLogOracle194l(listOf(esc)))
        // “</”里的斜杠要转义；单独的斜杠不转义；控制字符走 \u 形。
        assertEquals("\"<\\/\"", quoteOracle194l("</"))
        assertEquals("\"/\"", quoteOracle194l("/"))
        assertEquals("\"a\\u0001b\"", quoteOracle194l("a\u0001b"))
        assertEquals("\"a\\u0085b\"", quoteOracle194l("a\u0085b"))
        assertEquals("\"a\\u2028b\"", quoteOracle194l("a\u2028b"))
        assertEquals("\"a\u007fb\"", quoteOracle194l("a\u007fb"))
        // Long 边界原样输出，不截断。
        val big = CallLogEntry(
            id = "", peerId = "", peerName = "", isVideo = false,
            direction = Direction.INCOMING, state = State.ANSWERED,
            startedAt = Long.MIN_VALUE, durationMs = Long.MAX_VALUE, isGroup = false
        )
        assertEquals(
            "[{\"dur\":9223372036854775807,\"at\":-9223372036854775808,\"peer\":\"\",\"name\":\"\",\"id\":\"\",\"video\":false,\"state\":\"answered\",\"dir\":\"in\",\"group\":false}]",
            encodeCallLog(listOf(big))
        )
    }
}
