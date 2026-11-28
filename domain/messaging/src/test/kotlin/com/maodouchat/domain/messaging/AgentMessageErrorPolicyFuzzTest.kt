package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for AgentMessageErrorPolicy (checklist line
// 931, 63rd block). Test-only, zero production changes. The oracle is a
// hand-written null check: null takes the "star sync failed" fallback,
// anything else (including "") is inserted verbatim. Random messages mix
// null, empty, pool strings and random strings (including lone
// surrogates and template-shaped text).
class AgentMessageErrorPolicyFuzzTest {

// ---------- C2 AgentMessageErrorPolicy oracle ----------

// The two error texts, as literals (never read from production).
private fun messageNotFoundOracle(): String = "Error: message not found"

private fun onlySenderCanRevokeOracle(): String =
    "Error: only the sender can revoke this message"

// Hand-written null check: null takes the fallback, anything else
// (including "") is inserted verbatim.
private fun starSyncFailedOracle(message: String?): String =
    if (message == null) "Error: star sync failed" else "Error: " + message

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended (with the
// multi-code-unit uppercase expansions), ligatures, fullwidth forms,
// Deseret / Adlam / Medefaidrin / Vithkuqi astral letters, CJK, combining
// marks, digits of several scripts, and general punctuation. Osage small
// letters are excluded: JDK 17 leaves them unchanged on uppercase while
// newer Unicode data maps them (pinned separately).
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0661 to 0x0661, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E61 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F610 to 0x1F610
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> {
                // Lone surrogate (high or low): survives take/uppercase
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 42; 4 -> -42
    5 -> Int.MIN_VALUE; 6 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 42L; 4 -> -42L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

// ---------- block-63 generators ----------

// Field pool for required(): real field names plus strings that stress
// the " and " join (empty, blank, the separator word itself, a field
// that already contains " and ", template shapes, lone surrogates).
private val FIELD_POOL = arrayOf(
    "", " ", "userId", "chatId", "title", "messageId", "starred", "emoji",
    "a", "b", "c", "and", "required", "Error:", "\$x", "\${x}", "chatId and title",
    "\ud800", "\udc00", "\ud83d\ude10", "  padded  ", "\u000a", "0", "-1",
    "pinned", "muted", "archived", "markedUnread"
)

private fun randomFieldList(rnd: Random): List<String> {
    val n = when (rnd.nextInt(7)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 5
        else -> rnd.nextInt(0, 7)
    }
    val out = mutableListOf<String>()
    repeat(n) {
        out.add(if (rnd.nextInt(4) == 0) randomString(rnd, 12) else FIELD_POOL[rnd.nextInt(FIELD_POOL.size)])
    }
    return out
}

// Nullable message for starSyncFailed: null / empty / pool / random.
private val MSG_POOL = arrayOf(
    "", " ", "timeout", "star sync failed", "Error: boom", "\$x", "\${x}",
    "\ud800", "\udc00", "\ud83d\ude10", "  padded  ", "\u000a", "0"
)

private fun randomNullableMsg(rnd: Random): String? = when (rnd.nextInt(10)) {
    0 -> null
    1 -> ""
    2, 3 -> MSG_POOL[rnd.nextInt(MSG_POOL.size)]
    else -> randomString(rnd, 24)
}

// Payload for formatHttpError: lengths cluster on the 240 boundary and
// blank-ness is forced both ways, so take(240)+ifBlank is exercised on
// both sides of each edge.
private fun randomPayload(rnd: Random): String {
    val mode = rnd.nextInt(6)
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 239; 3 -> 240; 4 -> 241; 5 -> 300
        6 -> rnd.nextInt(0, 260)
        else -> rnd.nextInt(0, 320)
    }
    val sb = StringBuilder()
    when (mode) {
        0 -> repeat(len) { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]) }
        1 -> {
            // Blank for the first 240 units, non-blank after: the taken
            // prefix is blank, so the fallback must win.
            repeat(len) { i ->
                if (i < 240) sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
                else sb.append('x')
            }
        }
        2 -> {
            // Non-blank inside the first 240 units, blank after.
            repeat(len) { i ->
                if (i < 240 && rnd.nextInt(3) == 0) sb.append('x')
                else sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
            }
        }
        3 -> {
            // U+0085 NEL: not blank per the probed set, so a payload of
            // only NELs is NOT blank and must survive verbatim.
            repeat(len) { sb.append('\u0085') }
        }
        else -> {
            var n = 0
            while (n < len) {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomPrefix(rnd: Random): String = when (rnd.nextInt(6)) {
    0 -> ""
    1 -> " "
    2 -> arrayOf("模型接口", "语音接口", "Error", "\$x", "a:b", "x y")[rnd.nextInt(6)]
    3 -> randomString(rnd, 8)
    4 -> "\ud800"
    else -> randomString(rnd, 16)
}

private fun randomFallback(rnd: Random): String = when (rnd.nextInt(5)) {
    0 -> ""
    1 -> " "
    2 -> arrayOf("Server Error", "Bad Request", "Service Unavailable")[rnd.nextInt(3)]
    3 -> randomString(rnd, 16)
    else -> randomString(rnd)
}

    @Test
    fun starSyncFailedRandom() {
        val rnd = Random(42)
        repeat(300) {
            val msg = randomNullableMsg(rnd)
            assertEquals(starSyncFailedOracle(msg), AgentMessageErrorPolicy.starSyncFailed(msg))
        }
    }

    @Test
    fun pinnedEdgeCases() {

        // The two error texts, verbatim.
        assertEquals("Error: message not found", AgentMessageErrorPolicy.messageNotFound())
        assertEquals("Error: only the sender can revoke this message", AgentMessageErrorPolicy.onlySenderCanRevoke())
        // starSyncFailed: null takes the fallback, anything else
        // (including "") is inserted verbatim.
        assertEquals("Error: star sync failed", AgentMessageErrorPolicy.starSyncFailed(null))
        assertEquals("Error: ", AgentMessageErrorPolicy.starSyncFailed(""))
        assertEquals("Error:  ", AgentMessageErrorPolicy.starSyncFailed(" "))
        assertEquals("Error: timeout", AgentMessageErrorPolicy.starSyncFailed("timeout"))
        assertEquals("Error: star sync failed", AgentMessageErrorPolicy.starSyncFailed("star sync failed"))
        assertEquals("Error: Error: boom", AgentMessageErrorPolicy.starSyncFailed("Error: boom"))
        assertEquals("Error: \$x", AgentMessageErrorPolicy.starSyncFailed("\$x"))
        assertEquals("Error: \${x}", AgentMessageErrorPolicy.starSyncFailed("\${x}"))
        assertEquals("Error: \ud800", AgentMessageErrorPolicy.starSyncFailed("\ud800"))
        assertEquals("Error: \udc00", AgentMessageErrorPolicy.starSyncFailed("\udc00"))
        assertEquals("Error: \ud83d\ude10", AgentMessageErrorPolicy.starSyncFailed("\ud83d\ude10"))
        assertEquals("Error:   padded  ", AgentMessageErrorPolicy.starSyncFailed("  padded  "))
        assertEquals("Error: \u000a", AgentMessageErrorPolicy.starSyncFailed("\u000a"))
        assertEquals("Error: 0", AgentMessageErrorPolicy.starSyncFailed("0"))
        assertEquals("Error: \u0085", AgentMessageErrorPolicy.starSyncFailed("\u0085"))
        assertEquals("Error: \u00a0", AgentMessageErrorPolicy.starSyncFailed("\u00a0"))
        assertEquals("Error: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", AgentMessageErrorPolicy.starSyncFailed("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))    }
}
