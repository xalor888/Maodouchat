package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// unesc restated by hand: the two control chars stand for | and ^.
internal fun unesc173(s: String): String =
    s.replace("\u0001", "|").replace("\u0002", "^")

// toIntOrNull restated by hand: optional sign, then digits only,
// where a digit is any Unicode decimal digit (Character.digit
// semantics: Arabic-Indic and fullwidth digits count, a superscript
// two does not); the value must stay in Int range. A digit outside
// the BMP is two UTF-16 units, neither of which is a digit.
internal fun parseIntOrNull173(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (s.length == 1) return null
    }
    var v = 0L
    while (i < s.length) {
        val d = s[i].digitToIntOrNull(10) ?: return null
        v = v * 10 + d
        if (v > 2147483648L) return null
        i++
    }
    val signed = if (neg) -v else v
    if (signed < Int.MIN_VALUE || signed > Int.MAX_VALUE) return null
    return signed.toInt()
}

// parseDice restated by hand: DICE: prefix, head before |, sides:value.
// No range check, extra parts ignored, the tail after | never parsed.
internal fun parseDiceOracle173(content: String): Pair<Int, Int>? {
    if (!content.startsWith("DICE:")) return null
    val body = content.removePrefix("DICE:")
    val head = unesc173(body.substringBefore('|'))
    val parts = head.split(':')
    if (parts.size < 2) return null
    val sides = parseIntOrNull173(parts[0]) ?: return null
    val value = parseIntOrNull173(parts[1]) ?: return null
    return sides to value
}

// parseLuckyDraw restated by hand: LUCKY: prefix, picker before |,
// target after it (raw, never unescaped); either side blank is null.
internal fun parseLuckyDrawOracle173(content: String): Pair<String, String>? {
    if (!content.startsWith("LUCKY:")) return null
    val body = content.removePrefix("LUCKY:")
    val a = unesc173(body.substringBefore('|'))
    val b = body.substringAfter('|', "")
    if (a.isBlank() || b.isBlank()) return null
    return a to b
}

// Production parseDice returns the sides/value pair.
internal fun parseDiceProduction173(content: String): Pair<Int, Int>? =
    GroupPlayPolicy.parseDice(content)

// Production parseLuckyDraw returns the picker/target pair.
internal fun parseLuckyDrawProduction173(content: String): Pair<String, String>? =
    GroupPlayPolicy.parseLuckyDraw(content)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonPlay173(s: String): String {
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
                if (ch.code < 0x20) {
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

internal data class PlayCase173(val content: String)

internal val PLAY_PREFIX_POOL173: List<String> = listOf(
    "DICE:", "LUCKY:", "dice:", "Dice:", "DICE", "LUCKY", "", "DICE::", "LUCKY::", "DICE: "
)

internal val PLAY_NUM_POOL173: List<String> = listOf(
    "6", "3", "0", "-1", "+6", "06", "2147483647", "2147483648",
    "-2147483648", "-2147483649", "", " ", " 6", "6 ", "6.5", "1e3",
    "+", "-", "00", "0006", "99999999999999999999", "6x", "x6"
)

internal val PLAY_WORD_POOL173: List<String> = listOf(
    "Alice", "Bob", "", " ", "  ", "a|b", "a^b", "a\u0001b", "a\u0002b",
    "x:y", "|", "^", "\u0001", "\u0002", "🎉", "a b", "A|B^C", "a:b:c"
)

internal val PLAY_TAIL_POOL173: List<String> = listOf(
    "", "|tail", "|Alice rolled 3 / 6", "|", "||", "|a|b"
)

internal fun randomPlayCase173(rnd: Random): PlayCase173 {
    return when (rnd.nextInt(3)) {
        0 -> {
            val sides = PLAY_NUM_POOL173[rnd.nextInt(PLAY_NUM_POOL173.size)]
            val value = PLAY_NUM_POOL173[rnd.nextInt(PLAY_NUM_POOL173.size)]
            val tail = PLAY_TAIL_POOL173[rnd.nextInt(PLAY_TAIL_POOL173.size)]
            PlayCase173("DICE:$sides:$value$tail")
        }
        1 -> {
            val a = PLAY_WORD_POOL173[rnd.nextInt(PLAY_WORD_POOL173.size)]
            val b = PLAY_WORD_POOL173[rnd.nextInt(PLAY_WORD_POOL173.size)]
            PlayCase173("LUCKY:$a|$b")
        }
        else -> {
            val prefix = PLAY_PREFIX_POOL173[rnd.nextInt(PLAY_PREFIX_POOL173.size)]
            val word = PLAY_WORD_POOL173[rnd.nextInt(PLAY_WORD_POOL173.size)]
            val num = PLAY_NUM_POOL173[rnd.nextInt(PLAY_NUM_POOL173.size)]
            PlayCase173(prefix + word + num)
        }
    }
}

internal fun playWireJson173(c: PlayCase173): String =
    "{\"content\":${escJsonPlay173(c.content)}}"

class GroupPlayPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val diceCases = listOf(
            "DICE:6:3" to Pair(6, 3),
            "DICE:6:3|Alice rolled 3 / 6" to Pair(6, 3),
            "DICE:6" to null,
            "DICE:" to null,
            "DICE:6:" to null,
            "DICE::3" to null,
            "DICE:6:3:9" to Pair(6, 3),
            "DICE:0:0" to Pair(0, 0),
            "DICE:-6:-3" to Pair(-6, -3),
            "DICE:+6:+3" to Pair(6, 3),
            "DICE:06:03" to Pair(6, 3),
            "DICE:2147483647:2147483647" to Pair(2147483647, 2147483647),
            "DICE:2147483648:3" to null,
            "DICE:-2147483648:-2147483648" to Pair(-2147483648, -2147483648),
            "DICE:-2147483649:3" to null,
            "DICE: 6:3" to null,
            "DICE:6 :3" to null,
            "DICE:6: 3" to null,
            "dice:6:3" to null,
            "DICE:6.5:3" to null,
            "DICE:99999999999999999999:3" to null,
            // An escaped | inside the head unescapes first, so the
            // sides token carries a literal | and fails to parse.
            "DICE:6\u0001:3" to null,
            "DICE:6:3\u0001x" to null,
            // An escaped ^ unescapes harmlessly inside the numbers.
            "DICE:6:3\u0002" to null,
            "DICE:6:3|" to Pair(6, 3),
            // Non-ASCII decimal digits count too (Character.digit):
            // Arabic-Indic and fullwidth digits parse, a superscript
            // two does not, and an astral digit is two UTF-16 units
            // neither of which is a digit.
            "DICE:\u0663:\u0663" to Pair(3, 3),
            "DICE:\u0663\u0033:\u0033\u0663" to Pair(33, 33),
            "DICE:\uFF16:\uFF13" to Pair(6, 3),
            "DICE:\u00B2:3" to null,
            "DICE:\uD801\uDCA0:3" to null,
            "" to null,
            "DICE" to null,
            "hello" to null
        )
        for ((content, expected) in diceCases) {
            assertEquals(expected, parseDiceOracle173(content), "oracle-dice($content)")
            assertEquals(expected, parseDiceProduction173(content), "production-dice($content)")
        }
        val luckyCases = listOf(
            "LUCKY:Alice|Bob" to Pair("Alice", "Bob"),
            "LUCKY:Alice" to null,
            "LUCKY:|Bob" to null,
            "LUCKY:Alice|" to null,
            "LUCKY: |Bob" to null,
            "LUCKY:Alice| " to null,
            "LUCKY:  |  " to null,
            // The picker unescapes, so an escaped | becomes a real one.
            "LUCKY:\u0001|Bob" to Pair("|", "Bob"),
            "LUCKY:Alice\u0002|Bob" to Pair("Alice^", "Bob"),
            // The target never unescapes: the control char survives.
            "LUCKY:Alice|B\u0001ob" to Pair("Alice", "B\u0001ob"),
            "LUCKY:Alice|B\u0002ob" to Pair("Alice", "B\u0002ob"),
            // substringAfter takes the whole rest, pipes included.
            "LUCKY:Alice|Bob|Extra" to Pair("Alice", "Bob|Extra"),
            "LUCKY:Alice|Bob:Carol" to Pair("Alice", "Bob:Carol"),
            "lucky:Alice|Bob" to null,
            "LUCKY:" to null,
            "" to null,
            "LUCKY:🎉|🎯" to Pair("🎉", "🎯")
        )
        for ((content, expected) in luckyCases) {
            assertEquals(expected, parseLuckyDrawOracle173(content), "oracle-lucky($content)")
            assertEquals(expected, parseLuckyDrawProduction173(content), "production-lucky($content)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(173003)
        repeat(4000) {
            val c = randomPlayCase173(rnd)
            assertEquals(
                parseDiceOracle173(c.content), parseDiceProduction173(c.content),
                "dice(random $c)"
            )
            assertEquals(
                parseLuckyDrawOracle173(c.content), parseLuckyDrawProduction173(c.content),
                "lucky(random $c)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Dice: every sides token crossed with every value token,
        // with and without a tail; plus every prefix variant.
        for (sides in PLAY_NUM_POOL173) {
            for (value in PLAY_NUM_POOL173) {
                for (tail in listOf("", "|tail")) {
                    val content = "DICE:$sides:$value$tail"
                    assertEquals(
                        parseDiceOracle173(content), parseDiceProduction173(content),
                        "dice-sweep($content)"
                    )
                }
            }
        }
        for (prefix in PLAY_PREFIX_POOL173) {
            val content = prefix + "6:3"
            assertEquals(
                parseDiceOracle173(content), parseDiceProduction173(content),
                "dice-prefix($content)"
            )
            val lucky = prefix + "Alice|Bob"
            assertEquals(
                parseLuckyDrawOracle173(lucky), parseLuckyDrawProduction173(lucky),
                "lucky-prefix($lucky)"
            )
        }
        // Lucky: every picker crossed with every target.
        for (a in PLAY_WORD_POOL173) {
            for (b in PLAY_WORD_POOL173) {
                val content = "LUCKY:$a|$b"
                assertEquals(
                    parseLuckyDrawOracle173(content), parseLuckyDrawProduction173(content),
                    "lucky-sweep($content)"
                )
            }
        }
    }
}
