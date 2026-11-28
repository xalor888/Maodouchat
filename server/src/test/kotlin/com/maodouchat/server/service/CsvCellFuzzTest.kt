package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------- oracle ----------

// Independent re-implementation of csvCell: walk the string by hand
// instead of trimStart()/replace(), so a slip in either side shows up.
private fun csvCellOracle191(value: Any?): String {
    val raw = value?.toString() ?: ""
    var i = 0
    while (i < raw.length && raw[i].isWhitespace()) i++
    val first = if (i < raw.length) raw[i] else null
    val safe = if (first == '=' || first == '+' || first == '-' || first == '@') "'$raw" else raw
    val sb = StringBuilder()
    sb.append('"')
    for (c in safe) {
        if (c == '"') sb.append("\"\"") else sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}

private val CSV_VALUE_POOL191: List<Any?> = listOf(
    null, "", " ", "a", "hello", "a,b", "\"", "\"\"", "\"a\"",
    "=CMD()", "+2+2", "-x", "@user", " =CMD()", "\t=CMD()",
    "\u00A0@x", "x\"y\"z", "a\nb", "a\rb", "a\tb", "line1\nline2",
    "1", "0", "-1", "1.5", "true", "false", "null",
    "中文", "émoji 😀", "a\uD800b", "'", "''", "=", "+", "-", "@",
    "==", " @", "\u2003=x", "\uFEFF=x", "\u0085=x"
)

private const val CSV_ALPHABET191 =
    "ab=+-\u0040\"', \t\n\r\u00A0\u2003\uFEFF\u0085\u00E9\u4E2D\uD83D\uDE00\uD800x019"

private fun randomCsvString191(r: Random): String {
    if (r.nextInt(4) == 0) return CSV_VALUE_POOL191.filterIsInstance<String>().random(r)
    val n = r.nextInt(0, 30)
    return buildString {
        repeat(n) { append(CSV_ALPHABET191.random(r)) }
    }
}

private fun randomCsvValue191(r: Random): Any? {
    if (r.nextInt(4) == 0) return CSV_VALUE_POOL191.random(r)
    return when (r.nextInt(6)) {
        0 -> randomCsvString191(r)
        1 -> r.nextInt()
        2 -> r.nextLong()
        3 -> r.nextBoolean()
        4 -> null
        else -> randomCsvString191(r)
    }
}

// ---------- tests ----------

class CsvCellFuzzTest {

    @Test
    fun csvCellDifferential191() {
        val r = Random(191001)
        repeat(300) {
            val value = randomCsvValue191(r)
            assertEquals(csvCellOracle191(value), csvCell(value))
        }
    }

    @Test
    fun csvCellFormulaGuardDifferential191() {
        val r = Random(191002)
        repeat(300) {
            val prefix = when (r.nextInt(6)) {
                0 -> ""
                1 -> " "
                2 -> "  \t "
                3 -> "\u00A0"
                4 -> "\u2003\u2003"
                else -> "\n"
            }
            val head = "=+-@x\"".random(r).toString()
            val tail = randomCsvString191(r)
            val value = prefix + head + tail
            assertEquals(csvCellOracle191(value), csvCell(value))
            assertEquals(csvCellOracle191(prefix + "a" + tail), csvCell(prefix + "a" + tail))
        }
    }

    @Test
    fun csvCellPinned191() {
        assertEquals("\"\"", csvCell(null))
        assertEquals("\"\"", csvCell(""))
        assertEquals("\"a\"", csvCell("a"))
        assertEquals("\"a,b\"", csvCell("a,b"))
        assertEquals("\"\"\"\"\"\"", csvCell("\"\""))
        assertEquals("\"\"\"a\"\"\"", csvCell("\"a\""))
        assertEquals("\"'=CMD()\"", csvCell("=CMD()"))
        assertEquals("\"'+2+2\"", csvCell("+2+2"))
        assertEquals("\"'-x\"", csvCell("-x"))
        assertEquals("\"'@user\"", csvCell("@user"))
        assertEquals("\"' =CMD()\"", csvCell(" =CMD()"))
        assertEquals("\"\"\"\"", csvCell("\""))
        assertEquals("\"1\"", csvCell(1))
        assertEquals("\"'-1\"", csvCell(-1))
        assertEquals("\"true\"", csvCell(true))
        assertEquals("\"false\"", csvCell(false))
        assertEquals("\"2147483647\"", csvCell(Int.MAX_VALUE))
        assertEquals("\"'-2147483648\"", csvCell(Int.MIN_VALUE))
        assertEquals("\"9223372036854775807\"", csvCell(Long.MAX_VALUE))
        assertEquals("\"'-9223372036854775808\"", csvCell(Long.MIN_VALUE))
        // Only leading whitespace is skipped for the formula check; the
        // quote still goes in front of the untouched raw value.
        assertEquals("\"'  =x\"", csvCell("  =x"))
        assertEquals("\"  ax\"", csvCell("  ax"))
        // U+FEFF / U+0085 are not whitespace for trimStart, so the
        // formula guard does not fire through them.
        assertEquals("\"\uFEFF=x\"", csvCell("\uFEFF=x"))
        assertEquals("\"\u0085=x\"", csvCell("\u0085=x"))
        assertEquals(csvCellOracle191("a\uD800b\"c"), csvCell("a\uD800b\"c"))
        assertTrue(csvCell("x").startsWith("\"") && csvCell("x").endsWith("\""))
    }
}
