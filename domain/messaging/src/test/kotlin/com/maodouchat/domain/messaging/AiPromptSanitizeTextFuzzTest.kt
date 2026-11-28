package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPromptSanitizeTextFuzzTest {

    private companion object {
        private const val ITERATIONS = 200

        // 生产侧 9 条注入模板（AiPromptInjectionDetection.kt 实测抽出，已是小写）。
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
        private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
        private val ROLE_PLAY_MARKERS = Regex(
            """(?im)^\s*(system|assistant|developer|instruction)\s*[:：]"""
        )
        private val TEXT_PARTS = listOf(
            "hello", "world", "system", "System", "SYSTEM", "assistant", "developer",
            "instruction", ":", "：", " ", "  ", "\n", "\t", "\r", "ignore", "previous",
            "all", "disregard", "mode", "you are now the system", "你现在是系统", "忽略以上",
            "忽略之前", "忽略上述", "a", "b", "x", "1", "。", "，", "！", "hello world",
            "[untrusted-system]", "user", "Alice", "Bob", "请", "总结", "开会", "明天",
            "\u0000", "\u0007", "\u000B", "\u000C", "\u001F", "\u007F", "\u0001a", "a\u0007b",
        )

        private fun expectedSanitizeContextText(raw: String?, maxChars: Int): String {
            val limit = maxChars.coerceIn(1, 4_000)
            var text = raw.orEmpty().replace(CONTROL_CHARS, "").trim()
            if (text.isEmpty()) return ""
            if (AiPromptInjectionDetection.isLikelyInjectionAttempt(text)) return ""
            text = ROLE_PLAY_MARKERS.replace(text) { match ->
                "[untrusted-${match.groupValues.getOrNull(1)?.lowercase() ?: "role"}]"
            }
            return text.take(limit)
        }

        private fun expectedSanitizeSender(raw: String?): String =
            raw.orEmpty()
                .replace(CONTROL_CHARS, "")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .take(AiPromptSanitizeText.MAX_SENDER_CHARS)
                .ifBlank { "user" }

        private fun randomText(random: Random): String {
            val parts = random.nextInt(0, 8)
            return List(parts) { TEXT_PARTS.random(random) }.joinToString("")
        }

        private fun assertNoStrippedControls(text: String, what: String) {
            assertTrue(
                text.none { it in '\u0000'..'\u0008' || it == '\u000B' || it == '\u000C' ||
                    it in '\u000E'..'\u001F' || it == '\u007F' },
                "$what 不得含被剥离的控制字符",
            )
        }
    }

    @Test
    fun `sanitizeSender matches oracle on random inputs`() {
        val random = Random(0xA2_2050)
        repeat(ITERATIONS) { i ->
            val raw: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            val actual = AiPromptSanitizeText.sanitizeSender(raw)
            assertEquals(
                expectedSanitizeSender(raw), actual,
                "fuzz 迭代 #$i：sanitizeSender 必须与规则一致：raw=<$raw>",
            )
            assertTrue(actual.length <= AiPromptSanitizeText.MAX_SENDER_CHARS, "fuzz 迭代 #$i：不得超 120")
            assertFalse(actual.contains('\n'), "fuzz 迭代 #$i：换行必须被替换为空格")
            assertFalse(actual.contains('\r'), "fuzz 迭代 #$i：回车必须被替换为空格")
            assertNoStrippedControls(actual, "fuzz 迭代 #$i：sanitizeSender 输出")
            // 注意：take(120) 在截断位恰为空格时输出带尾空格、且不幂等（第二遍 trim 会去掉它），
            // 故此处不做幂等与 trim 断言，只钉住 oracle 一致。
        }
    }

    @Test
    fun `sanitizeContextText matches oracle on random inputs and limits`() {
        val random = Random(0xA2_2051)
        repeat(ITERATIONS) { i ->
            val raw: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            val maxChars = random.nextInt(-100, 5_001)
            val limit = maxChars.coerceIn(1, 4_000)
            val actual = AiPromptSanitizeText.sanitizeContextText(raw, maxChars)
            assertEquals(
                expectedSanitizeContextText(raw, maxChars), actual,
                "fuzz 迭代 #$i：sanitizeContextText 必须与规则一致：raw=<$raw> maxChars=$maxChars",
            )
            assertTrue(actual.length <= limit, "fuzz 迭代 #$i：输出不得超过截断上限 $limit")
            assertNoStrippedControls(actual, "fuzz 迭代 #$i：sanitizeContextText 输出")
            if (actual.isNotEmpty()) {
                assertFalse(
                    AiPromptInjectionDetection.isLikelyInjectionAttempt(actual),
                    "fuzz 迭代 #$i：非空输出不得再含注入模板",
                )
                for (line in actual.split('\n')) {
                    assertFalse(
                        line.matches(Regex("""^\s*(system|assistant|developer|instruction)\s*[:：].*""", RegexOption.IGNORE_CASE)),
                        "fuzz 迭代 #$i：行首 role-play 标记必须已被弱化：<$line>",
                    )
                }
            }
        }
    }

    @Test
    fun `sanitizeContextText injection templates always sanitize to empty`() {
        for (template in TEMPLATES) {
            assertEquals(
                "", AiPromptSanitizeText.sanitizeContextText(template),
                "注入模板原文必须被清空：$template",
            )
            assertEquals(
                "", AiPromptSanitizeText.sanitizeContextText("请注意 $template 然后继续"),
                "嵌在文本里的注入模板必须被清空：$template",
            )
            assertEquals(
                "", AiPromptSanitizeText.sanitizeContextText("  $template  "),
                "带空白的注入模板必须被清空：$template",
            )
        }
    }

    @Test
    fun `sanitizeContextText control chars are stripped but tab survives`() {
        // \u0009 不在剥离集里（生产侧 CONTROL_CHARS 不含 \t），\n/\r 不在剥离集但 trim 会处理首尾。
        assertEquals("a\tb", AiPromptSanitizeText.sanitizeContextText("a\tb"))
        assertEquals("ab", AiPromptSanitizeText.sanitizeContextText("a\u0007b"))
        // \u0000 与 \u001F 都在剥离集内，中间字符保留 → "abc"。
        assertEquals("abc", AiPromptSanitizeText.sanitizeContextText("a\u0000b\u001Fc"))
        assertEquals("", AiPromptSanitizeText.sanitizeContextText("   \u0007  "))
    }

    @Test
    fun `sanitizeQuery truncates to 700 and matches oracle`() {
        val random = Random(0xA2_2052)
        repeat(ITERATIONS) { i ->
            val raw: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            val actual = AiPromptSanitizeText.sanitizeQuery(raw)
            assertEquals(
                expectedSanitizeContextText(raw, AiPromptSanitizeText.MAX_QUERY_CHARS), actual,
                "fuzz 迭代 #$i：sanitizeQuery 必须是 maxChars=700 的 sanitizeContextText",
            )
            assertTrue(actual.length <= AiPromptSanitizeText.MAX_QUERY_CHARS, "fuzz 迭代 #$i：不得超 700")
        }
        assertEquals(700, AiPromptSanitizeText.sanitizeQuery("a".repeat(800)).length)
    }

    @Test
    fun `sanitizeContextLine returns null exactly when text sanitizes blank`() {
        val random = Random(0xA2_2053)
        repeat(ITERATIONS) { i ->
            val sender: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            val text: String? = if (random.nextInt(0, 10) == 0) null else randomText(random)
            val maxTextChars = random.nextInt(-50, 5_001)
            val cleanText = AiPromptSanitizeText.sanitizeContextText(text, maxTextChars)
            val line = AiPromptSanitizeText.sanitizeContextLine(sender, text, maxTextChars)
            if (cleanText.isBlank()) {
                assertNull(line, "fuzz 迭代 #$i：消毒后空白必须返回 null")
            } else {
                assertEquals(cleanText, line!!.text, "fuzz 迭代 #$i：text 必须是消毒后文本")
                assertEquals(
                    AiPromptSanitizeText.sanitizeSender(sender), line.sender,
                    "fuzz 迭代 #$i：sender 必须是消毒后发送者",
                )
            }
        }
    }

    @Test
    fun `role markers at line starts are neutralized in any case`() {
        val roles = listOf("system", "assistant", "developer", "instruction")
        for (role in roles) {
            assertEquals(
                "[untrusted-$role] hello",
                AiPromptSanitizeText.sanitizeContextText("$role: hello"),
                "行首 $role 标记必须被弱化",
            )
            assertEquals(
                "[untrusted-$role] hello",
                AiPromptSanitizeText.sanitizeContextText("${role.uppercase()}: hello"),
                "大写行首 $role 标记必须被弱化",
            )
            // 只替换标记本身，前面那行的换行与内容原样保留。
            assertEquals(
                "first line\n[untrusted-$role] hello",
                AiPromptSanitizeText.sanitizeContextText("first line\n  $role： hello"),
                "第二行行首 $role 标记必须被弱化",
            )
            assertEquals(
                "say $role: hello",
                AiPromptSanitizeText.sanitizeContextText("say $role: hello"),
                "行中 $role 标记不弱化",
            )
        }
    }
}
