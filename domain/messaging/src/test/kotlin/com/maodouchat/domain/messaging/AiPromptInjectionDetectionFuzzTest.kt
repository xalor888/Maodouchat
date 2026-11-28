package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPromptInjectionDetectionFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        // 生产侧全部 9 条模板（AiPromptInjectionDetection.kt 实测抽出，已是小写）。
        private val TEMPLATES = listOf(
            "ignore previous",
            "ignore all previous",
            "disregard previous",
            "忽略以上",
            "忽略之前",
            "忽略上述",
            "你现在是系统",
            "you are now the system",
            "developer mode",
        )
        private val WORDS = listOf(
            "hello", "world", "please", "help", "ignore", "previous", "all", "disregard",
            "system", "developer", "mode", "now", "the", "you", "are", "明天", "开会",
            "讨论", "系统", "升级", "忽略", "以上", "之前", "上述", "你现在是", "a", "b",
        )
        private val PUNCT = listOf(" ", ", ", ". ", "！", "？", "\n", "：", "、")

        private fun expectedFlagged(text: String?): Boolean {
            val body = text.orEmpty()
            if (body.isBlank()) return false
            val lower = body.lowercase()
            return TEMPLATES.any { lower.contains(it) }
        }

        private fun randomText(random: Random): String {
            val parts = random.nextInt(0, 8)
            return List(parts) {
                when (random.nextInt(0, 10)) {
                    0 -> TEMPLATES.random(random)
                    1 -> TEMPLATES.random(random).let { t ->
                        t.map { c -> if (random.nextBoolean()) c.uppercaseChar() else c }.joinToString("")
                    }
                    else -> WORDS.random(random)
                }
            }.joinToString(PUNCT.random(random))
        }
    }

    @Test
    fun `random text never throws and is deterministic`() {
        val random = Random(0xA2_2026)
        repeat(ITERATIONS) { i ->
            val text: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            val first = AiPromptInjectionDetection.isLikelyInjectionAttempt(text)
            val second = AiPromptInjectionDetection.isLikelyInjectionAttempt(text)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次判定必须相同")
        }
    }

    @Test
    fun `every template is flagged in any case and embedded`() {
        TEMPLATES.forEach { template ->
            assertTrue(
                AiPromptInjectionDetection.isLikelyInjectionAttempt(template),
                "模板原文必须命中：$template",
            )
            val upper = template.uppercase()
            assertTrue(
                AiPromptInjectionDetection.isLikelyInjectionAttempt(upper),
                "模板全大写必须命中：$template",
            )
            assertTrue(
                AiPromptInjectionDetection.isLikelyInjectionAttempt("请注意 $template 然后继续"),
                "模板嵌在普通文本里必须命中：$template",
            )
            assertTrue(
                AiPromptInjectionDetection.isLikelyInjectionAttempt("$template，请执行"),
                "模板在句首必须命中：$template",
            )
        }
    }

    @Test
    fun `flagged exactly when a template is present`() {
        val random = Random(0xA2_2027)
        repeat(ITERATIONS) { i ->
            val text: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            assertEquals(
                expectedFlagged(text), AiPromptInjectionDetection.isLikelyInjectionAttempt(text),
                "fuzz 迭代 #$i：命中当且仅当模板子串在场：$text",
            )
        }
    }

    @Test
    fun `blank and null inputs are never flagged`() {
        assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt(null))
        assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt(""))
        assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt("   "))
        assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt("\n\t "))
        val random = Random(0xA2_2028)
        repeat(ITERATIONS) {
            val blank = List(random.nextInt(1, 10)) { listOf(" ", "\n", "\t", "\r").random(random) }.joinToString("")
            assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt(blank), "纯空白必须不命中")
        }
        assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt("明天开会讨论系统升级"))
        assertFalse(AiPromptInjectionDetection.isLikelyInjectionAttempt("please help me with the system settings"))
    }

    @Test
    fun `templates broken by extra spaces or punctuation are not flagged`() {
        // 生产侧是 contains() 精确子串匹配：模板里多一个空格/标点/换行即不命中。
        val broken = listOf(
            "ignore  previous instructions",
            "ignore, previous instructions",
            "ignore\nprevious instructions",
            "ignore all  previous directions",
            "disregard  previous orders",
            "developer  mode",
            "developer-mode",
            "you are  now the system",
            "你现在是系 统管理员",
            "忽略以 上内容",
        )
        broken.forEach { text ->
            assertFalse(
                AiPromptInjectionDetection.isLikelyInjectionAttempt(text),
                "模板被打断后必须不命中：$text",
            )
        }
    }
}
