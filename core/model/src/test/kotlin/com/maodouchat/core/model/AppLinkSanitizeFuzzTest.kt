package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Deep-link sanitizers restated by hand. Ground truth, read from
// AppLinkSanitizers.kt / AppLinkEncoding.kt:
//   - sanitizeOpaqueId: cut at the first '/', '?' or '#' (in that order),
//     trim, take 128 UTF-16 units; blank or any whitespace / control
//     char (code < 0x20) rejects. sanitizeChatId / sanitizeMessageId /
//     sanitizePostId are thin wrappers over it.
//   - sanitizeInviteCode: same cut, trim, take 80; blank rejects;
//     only letters/digits, '_' and '-' survive.
//   - decodeComponent: '%' + two hex digits decodes to one Latin-1
//     char (no UTF-8 assembly); '%' needs i + 2 < length, bad hex
//     stays literal; '+' becomes a space.
internal fun sanitizeOpaqueIdOracle185(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(128) // AppLinkRouter.MAX_ID_LENGTH
    if (single.isBlank()) return null
    if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
    return single
}

internal fun sanitizeChatIdOracle185(raw: String): String? = sanitizeOpaqueIdOracle185(raw)

internal fun sanitizeMessageIdOracle185(raw: String): String? = sanitizeOpaqueIdOracle185(raw)

internal fun sanitizePostIdOracle185(raw: String): String? = sanitizeOpaqueIdOracle185(raw)

internal fun sanitizeInviteCodeOracle185(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(80) // AppLinkRouter.MAX_INVITE_CODE_LENGTH
    if (single.isBlank()) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
    return single
}

private val HEX185 = "0123456789ABCDEF".toCharArray()

internal fun decodeComponentOracle185(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = HEX185.indexOf(raw[i + 1].uppercaseChar())
            val lo = HEX185.indexOf(raw[i + 2].uppercaseChar())
            if (hi >= 0 && lo >= 0) {
                out.append(((hi shl 4) or lo).toChar())
                i += 3
                continue
            }
        }
        out.append(if (c == '+') ' ' else c)
        i++
    }
    return out.toString()
}

internal fun escJsonSanitize185(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun optSanitizeWireJson185(v: String?): String = v?.let { escJsonSanitize185(it) } ?: "null"

// ---------- production ----------

internal fun sanitizeChatIdProduction185(raw: String): String? = AppLinkRouter.sanitizeChatId(raw)

internal fun sanitizeMessageIdProduction185(raw: String): String? = AppLinkRouter.sanitizeMessageId(raw)

internal fun sanitizePostIdProduction185(raw: String): String? = AppLinkRouter.sanitizePostId(raw)

internal fun sanitizeInviteCodeProduction185(raw: String): String? = AppLinkRouter.sanitizeInviteCode(raw)

internal fun decodeComponentProduction185(raw: String): String = AppLinkRouter.decodeComponent(raw)

// ---------- generators ----------

internal val SANITIZE_RAWS185: List<String> = listOf(
    "",
    " ",
    "  ",
    "abc",
    "a/b",
    "a?b",
    "a#b",
    "a/b?c#d",
    "/abc",
    "abc/",
    "?",
    "#",
    "/",
    "a b",
    "a\tb",
    "a\nb",
    "a\u0001b",
    "a\u001Fb",
    "a\u007Fb",
    "a\u0085b",
    "a\u00A0b",
    "a\u2003b",
    "a\uFEFFb",
    "a\u3000b",
    "x".repeat(127),
    "x".repeat(128),
    "x".repeat(129),
    "x".repeat(200),
    "a_b-c.d",
    "a_b-c",
    "é",
    "İ",
    "ß",
    "a\uD83D\uDE00b",
    "  abc  ",
    "a".repeat(79),
    "a".repeat(80),
    "a".repeat(81),
    "a".repeat(100),
    "AbC-123_xYz",
    "a.b",
    "a:b",
    "a@b",
    "a=b",
    "a+b",
    "a%b",
    "a,b",
    "a;b",
    "a!b",
    "a~b",
    "a'b",
    "a\"b",
    "a\\b",
    "a|b",
    "a<b",
    "uuid-1234-5678-90ab-cdef",
    "pairKey_abc123",
    "12345",
    "x".repeat(100) + "/evil",
    "x".repeat(100) + "?evil",
    "  " + "y".repeat(130) + "  ",
    "µ",
    "ª",
    "²",
    "a\u0301b",
    "a\uD83D\uDE00".repeat(70),
    "a/b/c",
    "a?b?c",
    "a#b#c",
    "a#b/c?d",
    "\u0000",
    "\u000B",
    "\u000C",
    "a\u000Bb",
    "a\u000Cb",
)

internal val DECODE_RAWS185: List<String> = listOf(
    "",
    "%",
    "%%",
    "%2",
    "%41",
    "%41%42",
    "%4",
    "%4G",
    "%G4",
    "%zz",
    "a+b",
    "a%2Bb",
    "%2b",
    "%2B",
    "%2f",
    "%2F",
    "%00",
    "%ff",
    "%FF",
    "%80",
    "%c3%a9",
    "%C3%A9",
    "%e2%82%ac",
    "100%",
    "%41 at end%41",
    "a%41b%42c",
    "%41%41%41",
    "a+b+c",
    "++",
    "%+41",
    "%%41",
    "%41%",
    "%u1234",
    "%1",
    "x%4",
    "caf%C3%A9",
    "%7E",
    "%7e",
    "%3D",
    "%3d",
    "%0A",
    "%0a",
    "%0D",
    "%20",
    "%09",
    "a%20b",
    "ab%2",
    "ab%41",
    "%41ab",
    "İ%41",
    "ß%2F",
    "%2F%2F",
    "a%252Fb",
    "%252525",
    "%a",
    "%A",
    "%aB",
    "%Ab",
    "%1g",
    "%g1",
    "hello%20world",
    "%C3%28",
    "%28%29",
    "a%2Fb%3Fc%23d",
    "%41",
    "%4 1",
    "% 41",
    "%%%41",
    "%41%4",
    "%41%4G",
    "x".repeat(50) + "%41" + "y".repeat(50),
)

internal val SANITIZE_WHICH185: List<String> = listOf("chat", "message", "post", "invite")

internal data class SanitizeCase185(val raw: String, val which: String)

internal data class DecodeCase185(val raw: String)

internal fun randomSanitizeCase185(rnd: Random): SanitizeCase185 =
    SanitizeCase185(
        raw = when (rnd.nextInt(0, 4)) {
            0 -> SANITIZE_RAWS185.random(rnd)
            1 -> buildString {
                repeat(rnd.nextInt(0, 10)) {
                    append(listOf("a", "B", "1", "_", "-", ".", "/", "?", "#", " ", "%", "é", "\uD83D\uDE00").random(rnd))
                }
            }
            2 -> "x".repeat(rnd.nextInt(0, 140))
            else -> listOf("abc", "a/b", "a?b", "a#b", "a b", "a_b-c", "a.b", "a:b").random(rnd) +
                listOf("", "/", "?", "#", " ", "x".repeat(rnd.nextInt(0, 90))).random(rnd)
        },
        which = SANITIZE_WHICH185.random(rnd),
    )

internal fun randomDecodeCase185(rnd: Random): DecodeCase185 =
    DecodeCase185(
        raw = when (rnd.nextInt(0, 4)) {
            0 -> DECODE_RAWS185.random(rnd)
            1 -> buildString {
                repeat(rnd.nextInt(0, 12)) {
                    append(listOf("%", "%4", "%41", "%2F", "+", "a", "B", "%zz", "%0A", "%", "%ff").random(rnd))
                }
            }
            2 -> buildString {
                repeat(rnd.nextInt(0, 8)) {
                    append('%')
                    append(listOf("0", "4", "A", "f", "G", "z", "%").random(rnd))
                    append(listOf("1", "B", "F", "0", "g", "%").random(rnd))
                }
            }
            else -> "abc".repeat(rnd.nextInt(0, 3)) + "%" + rnd.nextInt(0, 256).toString(16)
        },
    )

internal fun sanitizeCaseWireJson185(c: SanitizeCase185): String =
    "{\"raw\":${escJsonSanitize185(c.raw)},\"which\":${escJsonSanitize185(c.which)}}"

internal fun decodeCaseWireJson185(c: DecodeCase185): String =
    "{\"raw\":${escJsonSanitize185(c.raw)}}"

class AppLinkSanitizeFuzzTest {

    private fun checkSanitize185(c: SanitizeCase185) {
        val oracle = when (c.which) {
            "chat" -> sanitizeChatIdOracle185(c.raw)
            "message" -> sanitizeMessageIdOracle185(c.raw)
            "post" -> sanitizePostIdOracle185(c.raw)
            else -> sanitizeInviteCodeOracle185(c.raw)
        }
        val production = when (c.which) {
            "chat" -> sanitizeChatIdProduction185(c.raw)
            "message" -> sanitizeMessageIdProduction185(c.raw)
            "post" -> sanitizePostIdProduction185(c.raw)
            else -> sanitizeInviteCodeProduction185(c.raw)
        }
        assertEquals(oracle, production, "sanitize(${c.which}, ${c.raw})")
    }

    private fun checkDecode185(c: DecodeCase185) {
        assertEquals(
            decodeComponentOracle185(c.raw),
            decodeComponentProduction185(c.raw),
            "decode(${c.raw})",
        )
    }

    @Test
    fun pinnedAppLinkSanitize() {
        // Opaque id: cut chars, 128 cap, blank / whitespace / control reject.
        for (raw in SANITIZE_RAWS185) {
            for (which in SANITIZE_WHICH185) {
                checkSanitize185(SanitizeCase185(raw, which))
            }
        }
        // Percent decode: valid hex, bad hex literal, '+' to space, end edge.
        for (raw in DECODE_RAWS185) {
            checkDecode185(DecodeCase185(raw))
        }
    }

    @Test
    fun randomAppLinkSanitizeMatchProduction() {
        val rnd = Random(185001)
        repeat(2500) { checkSanitize185(randomSanitizeCase185(rnd)) }
        repeat(1500) { checkDecode185(randomDecodeCase185(rnd)) }
    }

    @Test
    fun sweepAppLinkSanitizeBoundaries() {
        // Length edges around the 80 / 128 caps, with and without a cut char.
        for (n in listOf(0, 1, 78, 79, 80, 81, 82, 126, 127, 128, 129, 130, 200)) {
            for (which in SANITIZE_WHICH185) {
                checkSanitize185(SanitizeCase185("x".repeat(n), which))
                checkSanitize185(SanitizeCase185("x".repeat(n) + "/tail", which))
                checkSanitize185(SanitizeCase185("x".repeat(n) + " tail", which))
            }
        }
        // Every cut char at every position of a short id.
        for (cut in listOf('/', '?', '#')) {
            for (i in 0..4) {
                val raw = "ab".take(i) + cut + "cd".take(4 - i)
                for (which in SANITIZE_WHICH185) {
                    checkSanitize185(SanitizeCase185(raw, which))
                }
            }
        }
        // Percent-decode sweep: every hex pair edge.
        for (hi in listOf('0', '4', '9', 'A', 'F', 'a', 'f', 'G', 'g', '%', ' ')) {
            for (lo in listOf('0', '1', '9', 'A', 'F', 'a', 'f', 'G', 'g', '%', ' ')) {
                checkDecode185(DecodeCase185("%$hi$lo"))
                checkDecode185(DecodeCase185("x%$hi$lo" + "y"))
            }
        }
        // Surrogate-pair handling: astral chars are two UTF-16 units.
        checkSanitize185(SanitizeCase185("a\uD83D\uDE00".repeat(64), "chat"))
        checkSanitize185(SanitizeCase185("a\uD83D\uDE00".repeat(65), "chat"))
        checkDecode185(DecodeCase185("%D83D%DE00"))
    }
}
