package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeveloperQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(12)) {
        0 -> ""
        1 -> "   "
        2 -> "  abc  "
        3 -> "1"
        4 -> "0"
        5 -> "7"
        6 -> "90"
        7 -> "91"
        8 -> "-5"
        9 -> "99999"
        10 -> " 7 "
        else -> randomString(random, random.nextInt(1, 20))
    }

    private fun randomParams(random: Random, vararg names: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        names.forEach { name ->
            if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        }
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldDaysWay(params: Parameters): Int =
        (params["days"]?.toIntOrNull() ?: 7).coerceIn(1, 90)

    private fun oldCommandFilterWay(params: Parameters): String? =
        params["command"]?.trim()?.takeIf { it.isNotBlank() }

    @Test
    fun `analytics days old and new agree on seeded random params`() {
        val random = Random(37101)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random, "days", "other")
            assertEquals(
                oldDaysWay(params),
                parseDeveloperAnalyticsDays(params),
                "iteration " + i + ": days must match",
            )
        }
    }

    @Test
    fun `command filter old and new agree on seeded random params`() {
        val random = Random(37102)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random, "command", "since")
            assertEquals(
                oldCommandFilterWay(params),
                parseDeveloperCommandFilter(params),
                "iteration " + i + ": commandFilter must match",
            )
        }
    }

    @Test
    fun `analytics days boundaries are pinned`() {
        fun daysOf(value: String?): Int =
            parseDeveloperAnalyticsDays(if (value == null) parametersOf() else parametersOf("days" to listOf(value)))
        assertEquals(7, daysOf(null))
        assertEquals(7, daysOf("abc"))
        assertEquals(7, daysOf(""))
        assertEquals(7, daysOf(" 7 "))
        assertEquals(7, daysOf("7"))
        assertEquals(1, daysOf("-5"))
        assertEquals(1, daysOf("0"))
        assertEquals(1, daysOf("1"))
        assertEquals(90, daysOf("90"))
        assertEquals(90, daysOf("91"))
        assertEquals(90, daysOf("99999"))
    }

    @Test
    fun `command filter trims and blanks collapse to null`() {
        fun filterOf(value: String?): String? =
            parseDeveloperCommandFilter(if (value == null) parametersOf() else parametersOf("command" to listOf(value)))
        assertNull(filterOf(null))
        assertNull(filterOf(""))
        assertNull(filterOf("   "))
        assertEquals("abc", filterOf("abc"))
        assertEquals("abc", filterOf("  abc  "))
    }
}
