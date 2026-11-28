// Fuzz / random-oracle tests for MediaAutoDownloadPolicy
// (checklist line 931, 160th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: only the exact
// strings "always" and "off" pass through, anything else falls back to
// "wifi_only" -- "wifi_only" itself is not special-cased, it just lands
// in the fallback arm. Equality is exact (case-sensitive, no trimming).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun modeNormalizeOracle160(mode: String): String =
    if (mode == "always" || mode == "off") mode else "wifi_only"

// ---------- generators ----------

internal val MODE_POOL160 = listOf(
    "always", "off", "wifi_only", "ALWAYS", "Always", "aLwAyS", "OFF", "Off",
    "oFF", "WIFI_ONLY", "Wifi_Only", "", " ", "  ", "all", "alway", "alwayss",
    " always", "always ", "always\t", "always\n", "always\u00a0",
    "always\u0085", "always\u2003", "off ", " off", "off\u00a0", "wifi",
    "wifi_", "wifi_only ", " wifi_only", "of", "o", "a", "w", "\u00a0",
    "\u0085", "ß", "İ", "alwaysalways", "offoff",
)

internal val MODE_ALPHABET160 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003alwaysfwi_ALWAYSF WI\u0130\u0131I i\u00df\u1e9eK\u212a k\u017fsS\u00b5"

internal fun randomModeChar160(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> MODE_ALPHABET160[rnd.nextInt(MODE_ALPHABET160.length)].toString()
}

internal fun randomModeString160(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomModeChar160(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class ModeCase160(
    val mode: String,
)

internal fun randomModeCase160(rnd: Random): ModeCase160 {
    val mode = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> MODE_POOL160[rnd.nextInt(MODE_POOL160.size)]
        3 -> when (rnd.nextInt(3)) {
            0 -> "always"
            1 -> "off"
            else -> "wifi_only"
        }
        else -> randomModeString160(rnd)
    }
    return ModeCase160(mode)
}

internal fun modeJsonEscape160(s: String): String {
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

internal fun modeNormalizeWireJson160(case: ModeCase160): String =
    "{\"mode\":\"" + modeJsonEscape160(case.mode) + "\"}"

class MediaAutoDownloadPolicyFuzzTest {

    private fun check(mode: String) {
        assertEquals(
            modeNormalizeOracle160(mode),
            MediaAutoDownloadPolicy.normalizeMode(mode),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // The two exact modes pass through, toggle-independent.
        assertEquals("always", MediaAutoDownloadPolicy.normalizeMode("always"))
        assertEquals("off", MediaAutoDownloadPolicy.normalizeMode("off"))
        assertEquals("always", modeNormalizeOracle160("always"))
        assertEquals("off", modeNormalizeOracle160("off"))
        // "wifi_only" is the fallback value, not a pass-through case.
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("wifi_only"))
        // Near misses fall back: case, whitespace, extra chars.
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("ALWAYS"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("Always"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("OFF"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("Off"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("alway"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("always "))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode(" always"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("always\t"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("always\u00a0"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("off "))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode(" off"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("of"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode(""))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode(" "))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("\u00a0"))
        for (mode in MODE_POOL160) {
            check(mode)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(160001)
        repeat(2000) {
            val case = randomModeCase160(rnd)
            check(case.mode)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool mode.
        for (mode in MODE_POOL160) {
            check(mode)
        }
        // Every single alphabet char as the whole mode.
        for (c in MODE_ALPHABET160) {
            check(c.toString())
        }
        // "always" / "off" / "wifi_only" with every alphabet char as a prefix or a suffix.
        for (base in listOf("always", "off", "wifi_only")) {
            for (c in MODE_ALPHABET160) {
                check(c + base)
                check(base + c)
            }
        }
        // Leading / trailing blanks around the two modes, 0..20 long.
        for (n in 0..20) {
            check(" ".repeat(n) + "always")
            check("always" + " ".repeat(n))
            check(" ".repeat(n) + "off")
            check("off" + " ".repeat(n))
            check(" ".repeat(n))
        }
        // Repeated modes, 0..3 copies.
        for (n in 0..3) {
            check("always".repeat(n))
            check("off".repeat(n))
        }
        // Every pair of alphabet chars.
        for (a in MODE_ALPHABET160) {
            for (b in MODE_ALPHABET160) {
                check(a.toString() + b)
            }
        }
    }
}
