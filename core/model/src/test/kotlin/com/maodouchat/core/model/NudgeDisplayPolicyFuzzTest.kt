package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [NudgeDisplayPolicy] (checklist line 931, 35th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the display rewrite from the two prefixes
 * ("你拍了拍" / case-insensitive "You nudged ") and the four own/direct
 * branches, with sentinel templates ("YOU[…]", "THEY_YOU[…]",
 * "THEY_TARGET[…|…]") so every output is comparable verbatim. Note the two
 * fallbacks the oracle must reproduce exactly: a blank extracted target falls
 * back to the trimmed stored content, and an own message with a blank target
 * returns the raw stored content. A Python mini-model differentially checks
 * both oracles on 5000 random inputs each (0 diffs) before this file was
 * written.
 */
class NudgeDisplayPolicyFuzzTest {

    private val sentinel = Templates(
        youNudged = { t -> "YOU[$t]" },
        theyNudgedYou = { s -> "THEY_YOU[$s]" },
        theyNudgedTarget = { s, t -> "THEY_TARGET[$s|$t]" }
    )

    private fun oracleExtract(content: String): String {
        val t = content.trim()
        return when {
            t.startsWith("你拍了拍") -> t.removePrefix("你拍了拍").trim()
            t.startsWith("You nudged ", ignoreCase = true) -> t.substring("You nudged ".length).trim()
            else -> ""
        }
    }

    private fun oracleDisplay(own: Boolean, content: String, sender: String, direct: Boolean): String {
        val target = oracleExtract(content).ifBlank { content.trim() }
        if (own) return if (target.isNotBlank()) "YOU[$target]" else content
        val s = sender.trim().ifBlank { "…" }
        return if (direct) "THEY_YOU[$s]" else "THEY_TARGET[$s|${target.ifBlank { "…" }}]"
    }

    private fun randomContent(rnd: Random): String {
        val target = listOf("张三", "Alice", "  ", "", "李四 ", "bob@x", "a".repeat(20)).random(rnd)
        return when (rnd.nextInt(6)) {
            0 -> "你拍了拍$target"
            1 -> listOf("You nudged ", "YOU NUDGED ", "you Nudged ", "You Nudged ").random(rnd) + target
            2 -> "  你拍了拍$target  "
            3 -> listOf("hello", "你好", "拍了拍张三", "You nudged", "你拍了拍").random(rnd)
            4 -> ""
            else -> "   "
        }
    }

    private fun randomSender(rnd: Random): String =
        listOf("李四", "Alice", "  ", "", " Bob ", "x".repeat(15)).random(rnd)

    @Test
    fun extractTargetNameRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val content = randomContent(rnd)
            assertEquals(oracleExtract(content), NudgeDisplayPolicy.extractTargetName(content), "content=<$content>")
        }
    }

    @Test
    fun displayTextRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val own = rnd.nextBoolean()
            val content = randomContent(rnd)
            val sender = randomSender(rnd)
            val direct = rnd.nextBoolean()
            assertEquals(
                oracleDisplay(own, content, sender, direct),
                NudgeDisplayPolicy.displayText(own, content, sender, direct, sentinel),
                "own=$own content=<$content> sender=<$sender> direct=$direct"
            )
        }
    }

    @Test
    fun displayTextPins() {
        assertEquals("YOU[你拍了拍]", NudgeDisplayPolicy.displayText(true, "你拍了拍", "李四", false, sentinel))
        assertEquals("YOU[你拍了拍]", NudgeDisplayPolicy.displayText(true, "你拍了拍 ", "李四", false, sentinel))
        assertEquals("YOU[Bob]", NudgeDisplayPolicy.displayText(true, "YOU NUDGED Bob", "李四", false, sentinel))
        assertEquals("YOU[alice]", NudgeDisplayPolicy.displayText(true, "you NUDGED alice", "李四", true, sentinel))
        assertEquals("YOU[You nudged]", NudgeDisplayPolicy.displayText(true, "You nudged ", "李四", false, sentinel))
        assertEquals("YOU[hello]", NudgeDisplayPolicy.displayText(true, "hello", "李四", false, sentinel))
        assertEquals("   ", NudgeDisplayPolicy.displayText(true, "   ", "李四", false, sentinel))
        assertEquals("THEY_YOU[…]", NudgeDisplayPolicy.displayText(false, "你拍了拍张三", "   ", true, sentinel))
        assertEquals("THEY_TARGET[李四|…]", NudgeDisplayPolicy.displayText(false, "   ", "李四", false, sentinel))
        assertEquals("THEY_TARGET[李四|张三]", NudgeDisplayPolicy.displayText(false, "你拍了拍张三", "李四", false, sentinel))
        assertEquals("THEY_YOU[李四]", NudgeDisplayPolicy.displayText(false, "你拍了拍张三", " 李四 ", true, sentinel))
    }
}
