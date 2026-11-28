package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiOperationTitleKindFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val KNOWN = mapOf(
            "TRANSCRIBE_VOICE" to AiOperationTitleKind.TRANSCRIBE_VOICE,
            "TRANSLATE_MESSAGE" to AiOperationTitleKind.TRANSLATE_MESSAGE,
            "SUMMARIZE_MESSAGES" to AiOperationTitleKind.SUMMARIZE_MESSAGES,
            "ANALYZE_IMAGE" to AiOperationTitleKind.ANALYZE_IMAGE,
            "ANALYZE_FILE" to AiOperationTitleKind.ANALYZE_FILE,
        )
        private val FRAGMENTS = listOf(
            "TRANSCRIBE", "TRANSLATE", "SUMMARIZE", "ANALYZE", "VOICE", "MESSAGE", "MESSAGES",
            "IMAGE", "FILE", "CHAT", "GENERIC", "transcribe", "voice", "Summarize", "AnAlYzE",
            "_", "-", " ", "TRANSCRIBE_VOICE", "SUMMARIZE_MESSAGES", "x", "1",
        )

        private fun expectedKind(type: String): AiOperationTitleKind =
            KNOWN[type] ?: AiOperationTitleKind.GENERIC

        private fun randomType(random: Random): String {
            val parts = random.nextInt(1, 4)
            return List(parts) { FRAGMENTS.random(random) }.joinToString("")
        }
    }

    @Test
    fun `random type strings never throw and are deterministic`() {
        val random = Random(0xA1_2026)
        repeat(ITERATIONS) { i ->
            val type = randomType(random)
            val first = aiOperationTitleKind(type)
            val second = aiOperationTitleKind(type)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次判定必须相同")
        }
    }

    @Test
    fun `every known operation maps exactly and only exactly`() {
        KNOWN.forEach { (wire, kind) ->
            assertEquals(kind, aiOperationTitleKind(wire), "已知操作必须精确映射：$wire")
        }
        val random = Random(0xA1_2027)
        repeat(ITERATIONS) { i ->
            val type = randomType(random)
            assertEquals(expectedKind(type), aiOperationTitleKind(type), "fuzz 迭代 #$i：判定必须与五项表一致：$type")
        }
    }

    @Test
    fun `unknown strings always fall back to generic`() {
        val random = Random(0xA1_2028)
        repeat(ITERATIONS) { i ->
            val type = randomType(random)
            if (type !in KNOWN) {
                assertEquals(
                    AiOperationTitleKind.GENERIC, aiOperationTitleKind(type),
                    "fuzz 迭代 #$i：不在五项表里的输入必须归 GENERIC：$type",
                )
            }
        }
        assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind(""))
        assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind("SUMMARIZE_CHAT"))
        assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind("TRANSCRIBE_AUDIO"))
    }

    @Test
    fun `case variations of known names are generic`() {
        KNOWN.keys.forEach { wire ->
            assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind(wire.lowercase()), "小写必须不命中：$wire")
            assertEquals(
                AiOperationTitleKind.GENERIC,
                aiOperationTitleKind(wire.replaceFirstChar { it.lowercase() }),
                "首字母小写必须不命中：$wire",
            )
        }
        val random = Random(0xA1_2029)
        repeat(ITERATIONS) { i ->
            val wire = KNOWN.keys.random(random)
            val flipped = wire.map { if (random.nextBoolean()) it.lowercaseChar() else it }.joinToString("")
            if (flipped != wire) {
                assertEquals(
                    AiOperationTitleKind.GENERIC, aiOperationTitleKind(flipped),
                    "fuzz 迭代 #$i：大小写翻转后必须不命中：$flipped",
                )
            }
        }
    }

    @Test
    fun `near-miss names are generic`() {
        val nearMisses = KNOWN.keys.flatMap { wire ->
            listOf(
                "$wire ", " $wire", "$wire\n", "${wire}S", wire.dropLast(1),
                wire.replace('_', '-'), wire.replace("VOICE", "VOIC"), wire.replace("MESSAGE", "MESSAG"),
            )
        }.filter { it !in KNOWN }
        nearMisses.forEach { near ->
            assertEquals(AiOperationTitleKind.GENERIC, aiOperationTitleKind(near), "近似名必须归 GENERIC：$near")
        }
        assertTrue(nearMisses.size == KNOWN.size * 8 - 7, "近似名集合不得悄悄缩水")
    }
}
