package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiProviderFormPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 200

        // 各 clip 上限（AiProviderFormPolicy.kt 实测）。
        private val TEXT_CLIPS = listOf(
            AiProviderFormPolicy::clipName to 80,
            AiProviderFormPolicy::clipBaseUrl to 240,
            AiProviderFormPolicy::clipModel to 80,
            AiProviderFormPolicy::clipApiKey to 200,
            AiProviderFormPolicy::clipOrganization to 80,
            AiProviderFormPolicy::clipAnthropicVersion to 40,
            AiProviderFormPolicy::clipExtraHeaders to 1_000,
            AiProviderFormPolicy::clipTemperature to 8,
            AiProviderFormPolicy::clipTopP to 8,
        )
        private val DIGIT_CLIPS = listOf(
            AiProviderFormPolicy::clipContextWindow to 8,
            AiProviderFormPolicy::clipHistoryLimit to 3,
            AiProviderFormPolicy::clipMaxTokens to 6,
            AiProviderFormPolicy::clipTimeout to 3,
        )
        private val NUMERIC_INPUTS = listOf(
            "", "0", "512", "4096", "128000", " 512", "512 ", " 1.5 ", "1.5", "0.25",
            ".5", "1e3", "1E-2", "-3", "+7", "NaN", "Infinity", "-Infinity", "abc",
            "12a", "1,000", "0x10", "٣", "１２", "99999999999999999999", "1.7976931348623157e308",
            "１２３", "12 34", "１.５", ".", "-", "e", "1..2", "007", "2147483647",
            "2147483648", "-2147483648", "9223372036854775807",
        )
        private val TEXT_PARTS = listOf(
            "a", "Z", "0", "9", " ", "  ", "\n", "\t", ".", ",", "-", "_", "/", ":", "é",
            "٣", "１", "小", "明", "@", "#", "https://", "key-", "abc", "123", "e", "E",
            "+", "NaN", "Infinity", "{", "}", "\"", "'", "\\", "…",
        )

        private fun randomText(random: Random): String {
            val parts = random.nextInt(0, 12)
            return List(parts) { TEXT_PARTS.random(random) }.joinToString("")
        }

        private fun randomNumericText(random: Random): String {
            val parts = random.nextInt(0, 6)
            return List(parts) { NUMERIC_INPUTS.random(random) }.joinToString("")
        }
    }

    @Test
    fun `text clips take prefix at their limits and are idempotent`() {
        val random = Random(0xA2_2040)
        for ((clip, limit) in TEXT_CLIPS) {
            repeat(ITERATIONS) { i ->
                val input = randomText(random)
                val clipped = clip(input)
                assertEquals(
                    input.take(limit), clipped,
                    "fuzz 迭代 #$i：clip 必须是原串前 $limit 字符",
                )
                assertTrue(clipped.length <= limit, "fuzz 迭代 #$i：clip 输出不得超过 $limit")
                assertEquals(clipped, clip(clipped), "fuzz 迭代 #$i：clip 必须幂等")
            }
        }
    }

    @Test
    fun `digit clips keep only digits at their limits and are idempotent`() {
        val random = Random(0xA2_2041)
        for ((clip, limit) in DIGIT_CLIPS) {
            repeat(ITERATIONS) { i ->
                val input = randomText(random)
                val clipped = clip(input)
                assertEquals(
                    input.filter(Char::isDigit).take(limit), clipped,
                    "fuzz 迭代 #$i：digit clip 必须是去非数字后的前 $limit 字符",
                )
                assertTrue(clipped.all(Char::isDigit), "fuzz 迭代 #$i：digit clip 输出只能含数字")
                assertTrue(clipped.length <= limit, "fuzz 迭代 #$i：digit clip 输出不得超过 $limit")
                assertEquals(clipped, clip(clipped), "fuzz 迭代 #$i：digit clip 必须幂等")
            }
        }
    }

    @Test
    fun `digit clips keep unicode digits`() {
        // Char::isDigit 是 Unicode 数字判定，不只是 ASCII 0-9。
        assertEquals("٣", AiProviderFormPolicy.clipTimeout("a٣b"))
        assertEquals("１２", AiProviderFormPolicy.clipHistoryLimit("１２x"))
    }

    @Test
    fun `normalizeBaseUrl trims and drops trailing slashes`() {
        val random = Random(0xA2_2042)
        repeat(ITERATIONS) { i ->
            val input = randomText(random)
            val expected = input.trim().trimEnd('/')
            assertEquals(
                expected, AiProviderFormPolicy.normalizeBaseUrl(input),
                "fuzz 迭代 #$i：normalizeBaseUrl 必须是 trim 后去尾斜杠",
            )
            val once = AiProviderFormPolicy.normalizeBaseUrl(input)
            assertEquals(once, AiProviderFormPolicy.normalizeBaseUrl(once), "fuzz 迭代 #$i：必须幂等")
        }
        assertEquals("", AiProviderFormPolicy.normalizeBaseUrl(""))
        assertEquals("", AiProviderFormPolicy.normalizeBaseUrl("///"))
        assertEquals("https://x", AiProviderFormPolicy.normalizeBaseUrl("  https://x///  "))
    }

    @Test
    fun `normalizeName falls back only on blank`() {
        val random = Random(0xA2_2043)
        repeat(ITERATIONS) { i ->
            val input = randomText(random)
            val fallback = randomText(random)
            assertEquals(
                input.ifBlank { fallback },
                AiProviderFormPolicy.normalizeName(input, fallback),
                "fuzz 迭代 #$i：normalizeName 空白时回退、否则原样",
            )
        }
    }

    @Test
    fun `anthropic version and extra headers default only on blank`() {
        val random = Random(0xA2_2044)
        repeat(ITERATIONS) { i ->
            val input = randomText(random)
            assertEquals(
                input.trim().ifBlank { "2023-06-01" },
                AiProviderFormPolicy.normalizeAnthropicVersion(input),
                "fuzz 迭代 #$i：anthropic version 空白时回默认",
            )
            assertEquals(
                input.trim().ifBlank { "{}" },
                AiProviderFormPolicy.normalizeExtraHeaders(input),
                "fuzz 迭代 #$i：extra headers 空白时回默认",
            )
        }
    }

    @Test
    fun `temperature and topP parse trimmed doubles or null`() {
        val random = Random(0xA2_2045)
        for (input in NUMERIC_INPUTS) {
            assertDoubleEquals(
                input.trim().toDoubleOrNull(),
                AiProviderFormPolicy.parseTemperature(input),
                "parseTemperature(<$input>)",
            )
            assertDoubleEquals(
                input.trim().toDoubleOrNull(),
                AiProviderFormPolicy.parseTopP(input),
                "parseTopP(<$input>)",
            )
        }
        repeat(ITERATIONS) { i ->
            val input = randomNumericText(random)
            assertDoubleEquals(
                input.trim().toDoubleOrNull(),
                AiProviderFormPolicy.parseTemperature(input),
                "fuzz 迭代 #$i：parseTemperature(<$input>)",
            )
        }
    }

    @Test
    fun `int fields parse without trimming and fall back to defaults`() {
        // 注意：parse*Int 一律不 trim——" 512" 不是合法整数，回默认值。
        val random = Random(0xA2_2046)
        val cases = listOf(
            AiProviderFormPolicy::parseMaxTokens to 4_096,
            AiProviderFormPolicy::parseContextWindow to 128_000,
            AiProviderFormPolicy::parseHistoryLimit to 24,
            AiProviderFormPolicy::parseTimeout to 120,
        )
        for ((parse, default) in cases) {
            for (input in NUMERIC_INPUTS) {
                assertEquals(
                    input.toIntOrNull() ?: default, parse(input),
                    "parse(<$input>) 默认 $default",
                )
            }
            repeat(ITERATIONS) { i ->
                val input = randomNumericText(random)
                assertEquals(
                    input.toIntOrNull() ?: default, parse(input),
                    "fuzz 迭代 #$i：parse(<$input>) 默认 $default",
                )
            }
        }
        assertEquals(4_096, AiProviderFormPolicy.parseMaxTokens(" 512"))
        assertEquals(120, AiProviderFormPolicy.parseTimeout("12x3"))
        // "٣" 的 toIntOrNull 由 Character.digit 决定（Unicode 数字是否被接受），
        // 不手写期望，上面循环已用同一 toIntOrNull 做 oracle 逐项核对。
    }

    private fun assertDoubleEquals(expected: Double?, actual: Double?, what: String) {
        when {
            expected == null -> assertNull(actual, "$what 必须是 null")
            expected.isNaN() -> assertTrue(actual?.isNaN() == true, "$what 必须是 NaN")
            else -> assertEquals(expected, actual, what)
        }
    }
}
