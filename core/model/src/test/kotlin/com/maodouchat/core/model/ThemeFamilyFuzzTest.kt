package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Alias table reimplemented from ThemeFamily.normalize: trim, lowercase,
// then alias lookup, anything else falls back to MAODOU.
internal fun normalizeThemeOracle164(raw: String?): ThemeFamily {
    val key = raw?.trim()?.lowercase()
    return when (key) {
        "tg_classic", "tg", "telegram", "telegram_classic" -> ThemeFamily.TG_CLASSIC
        "tg_midnight", "midnight" -> ThemeFamily.TG_MIDNIGHT
        "tg_graphite", "graphite" -> ThemeFamily.TG_GRAPHITE
        else -> ThemeFamily.MAODOU
    }
}

// Flags derived from the enum itself, not the production getters.
internal fun themeFlagsOracle164(family: ThemeFamily): Pair<Boolean, Boolean> =
    (family == ThemeFamily.MAODOU) to (family != ThemeFamily.MAODOU)

// ---------- generators ----------

internal val THEME_ALIASES164 = listOf(
    "tg_classic", "tg", "telegram", "telegram_classic",
    "tg_midnight", "midnight", "tg_graphite", "graphite",
)

internal val THEME_POOL164 = listOf(
    null, "", " ", "   ", "\t", "\n", "\u00a0", "\u2003",
    "maodou", "MAODOU", "Maodou", "unknown", "unknown_random",
    "tg-classic", "tg_classic2", "tg_classi", "tg_", "_tg",
    "telegramclassic", "telegram_classi", "midnigh", "graphit",
    "t", "g", "tg ", " tg", "TG_CLASSIC", "Tg_Classic", "Telegram",
    "TG_MIDNIGHT", "Midnight", "TG_GRAPHITE", "Graphite",
    " tg_classic ", "\ttg_midnight\t", "  graphite  ",
    "TG", "TELEGRAM", "TELEGRAM_CLASSIC", "MIDNIGHT", "GRAPHITE",
    "tg_classic\u00a0", "\u00a0tg_classic", "tg\u2003midnight",
)

internal const val THEME_ALPHABET164 = "tg_cmidn "

internal fun randomThemeString164(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 13)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(THEME_ALPHABET164[rnd.nextInt(THEME_ALPHABET164.length)]) }
    return sb.toString()
}

internal fun randomThemeCase164(rnd: Random): String? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> THEME_POOL164[rnd.nextInt(THEME_POOL164.size)]
        2 -> {
            val alias = THEME_ALIASES164[rnd.nextInt(THEME_ALIASES164.size)]
            val sb = StringBuilder()
            for (ch in alias) sb.append(if (rnd.nextBoolean()) ch.uppercaseChar() else ch)
            val pad = rnd.nextInt(3)
            val left = " ".repeat(pad)
            val right = " ".repeat(rnd.nextInt(3))
            left + sb.toString() + right
        }
        else -> randomThemeString164(rnd)
    }
}

internal fun themeWireJson164(raw: String?): String {
    val sb = StringBuilder()
    sb.append("{")
    if (raw == null) {
        sb.append("\"raw\":null")
    } else {
        sb.append("\"raw\":\"")
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        val v = c.code
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
        sb.append("\"")
    }
    sb.append("}")
    return sb.toString()
}

class ThemeFamilyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            null to ThemeFamily.MAODOU,
            "" to ThemeFamily.MAODOU,
            " " to ThemeFamily.MAODOU,
            "   " to ThemeFamily.MAODOU,
            "\t" to ThemeFamily.MAODOU,
            "\u00a0" to ThemeFamily.MAODOU,
            "maodou" to ThemeFamily.MAODOU,
            "MAODOU" to ThemeFamily.MAODOU,
            "tg_classic" to ThemeFamily.TG_CLASSIC,
            "tg" to ThemeFamily.TG_CLASSIC,
            "telegram" to ThemeFamily.TG_CLASSIC,
            "telegram_classic" to ThemeFamily.TG_CLASSIC,
            "TG_CLASSIC" to ThemeFamily.TG_CLASSIC,
            "Tg_Classic" to ThemeFamily.TG_CLASSIC,
            "tg_midnight" to ThemeFamily.TG_MIDNIGHT,
            "midnight" to ThemeFamily.TG_MIDNIGHT,
            "TG_MIDNIGHT" to ThemeFamily.TG_MIDNIGHT,
            "tg_graphite" to ThemeFamily.TG_GRAPHITE,
            "graphite" to ThemeFamily.TG_GRAPHITE,
            "TG_GRAPHITE" to ThemeFamily.TG_GRAPHITE,
            " tg_classic " to ThemeFamily.TG_CLASSIC,
            "\ttg_midnight\t" to ThemeFamily.TG_MIDNIGHT,
            "  graphite  " to ThemeFamily.TG_GRAPHITE,
            "tg-classic" to ThemeFamily.MAODOU,
            "tg_classic2" to ThemeFamily.MAODOU,
            "tg_classi" to ThemeFamily.MAODOU,
            "telegramclassic" to ThemeFamily.MAODOU,
            "midnigh" to ThemeFamily.MAODOU,
            "graphit" to ThemeFamily.MAODOU,
            "unknown_random" to ThemeFamily.MAODOU,
            "t" to ThemeFamily.MAODOU,
            "g" to ThemeFamily.MAODOU,
            "tg_" to ThemeFamily.MAODOU,
            "_tg" to ThemeFamily.MAODOU,
            "tg\u2003midnight" to ThemeFamily.MAODOU,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, ThemeFamily.normalize(raw), "normalize($raw)")
            assertEquals(expected, normalizeThemeOracle164(raw), "oracle($raw)")
        }
        for (family in ThemeFamily.entries) {
            val (glass, telegram) = themeFlagsOracle164(family)
            assertEquals(family.isLiquidGlass, glass, "isLiquidGlass($family)")
            assertEquals(family.isTelegram, telegram, "isTelegram($family)")
        }
        assertEquals(listOf("maodou", "tg_classic", "tg_midnight", "tg_graphite"), ThemeFamily.entries.map { it.id })
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(164001)
        repeat(4000) {
            val raw = randomThemeCase164(rnd)
            val expected = normalizeThemeOracle164(raw)
            assertEquals(expected, ThemeFamily.normalize(raw), "normalize($raw)")
            val (glass, telegram) = themeFlagsOracle164(expected)
            assertEquals(glass, expected.isLiquidGlass)
            assertEquals(telegram, expected.isTelegram)
        }
    }

    @Test
    fun sweepExhaustive() {
        fun sweep(prefix: String, depth: Int) {
            if (depth == 0) {
                assertEquals(normalizeThemeOracle164(prefix), ThemeFamily.normalize(prefix), "normalize($prefix)")
                return
            }
            sweep(prefix, depth - 1)
            for (ch in THEME_ALPHABET164) sweep(prefix + ch, depth - 1)
        }
        sweep("", 4)
        for (alias in THEME_ALIASES164) {
            val chars = alias.toList()
            val total = 1 shl chars.size
            for (mask in 0 until total) {
                val sb = StringBuilder()
                for (i in chars.indices) {
                    sb.append(if ((mask shr i) and 1 == 1) chars[i].uppercaseChar() else chars[i])
                }
                val raw = sb.toString()
                assertEquals(normalizeThemeOracle164(raw), ThemeFamily.normalize(raw), "normalize($raw)")
            }
        }
        for (raw in THEME_POOL164) {
            assertEquals(normalizeThemeOracle164(raw), ThemeFamily.normalize(raw), "normalize($raw)")
        }
    }
}
