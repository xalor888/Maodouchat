package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [BotCommandPolicy] (checklist line 931, 35th block).
 * Test-only, zero production changes.
 *
 * Oracles restate the production rules independently: the menu gate is
 * trimStart + '/' + no newline + trimmed length <= 64; the query strips the
 * leading '/', takes the first blank-separated word, drops any "@user" suffix
 * and lowercases; filtering is a case-sensitive prefix match against the
 * lowercased query, capped at 20; insertion spells "/cmd@user " for multi-bot
 * and "/cmd " otherwise. A Python mini-model differentially checks all five
 * oracles on 5000 random inputs each (0 diffs) before this file was written.
 */
class BotCommandPolicyFuzzTest {

    private val words = listOf(
        "help", "start", "settings", "ban", "mute", "kick", "info", "ping",
        "HELP", "Start", "HeLLo", "a", "ab", "x1", "cmd-2", "cmd_3", "a".repeat(70)
    )
    private val users = listOf("alice", "Bob", "x", "bot_admin", "a".repeat(30))

    private fun oracleShouldOffer(draft: String): Boolean {
        val t = draft.trimStart()
        return t.startsWith("/") && !t.contains('\n') && t.length <= 64
    }

    private fun oracleQuery(draft: String): String {
        val t = draft.trimStart()
        val noSlash = if (t.startsWith("/")) t.substring(1) else t
        val firstWord = noSlash.substringBefore(" ")
        return firstWord.substringBefore("@").lowercase()
    }

    private fun oracleFilter(items: List<BotCommandItem>, draft: String): List<BotCommandItem> {
        if (!oracleShouldOffer(draft)) return emptyList()
        val q = oracleQuery(draft)
        if (q.isEmpty()) return items.take(20)
        return items.filter { it.command.startsWith(q) }.take(20)
    }

    private fun oracleInsert(item: BotCommandItem, multiBot: Boolean): String =
        if (multiBot) "/${item.command}@${item.username} " else "/${item.command} "

    private fun randomDraft(rnd: Random): String {
        val sb = StringBuilder()
        repeat(rnd.nextInt(0, 4)) { sb.append(listOf(' ', '\t', '\n', '\r').random(rnd)) }
        if (rnd.nextInt(4) != 0) sb.append('/')
        sb.append(words.random(rnd))
        when (rnd.nextInt(5)) {
            0 -> sb.append("@").append(users.random(rnd))
            1 -> sb.append(" ").append(words.random(rnd))
            2 -> sb.append("@").append(users.random(rnd)).append(" ").append(words.random(rnd))
            3 -> sb.append("\n").append(words.random(rnd))
        }
        if (rnd.nextInt(10) == 0) sb.append(" ".repeat(rnd.nextInt(60, 80)))
        return sb.toString()
    }

    private fun randomItem(rnd: Random): BotCommandItem = BotCommandItem(
        botId = "bot_" + rnd.nextInt(100),
        username = users.random(rnd),
        name = words.random(rnd),
        command = words.random(rnd),
        description = "",
    )

    @Test
    fun isBotUserIdRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val id = when (rnd.nextInt(4)) {
                0 -> "bot_" + words.random(rnd)
                1 -> words.random(rnd)
                2 -> "Bot_" + words.random(rnd)
                else -> "bot"
            }
            assertEquals(id.startsWith("bot_"), BotCommandPolicy.isBotUserId(id), "id=<$id>")
        }
    }

    @Test
    fun composerMenuRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val draft = randomDraft(rnd)
            assertEquals(oracleShouldOffer(draft), BotCommandPolicy.shouldOfferComposerMenu(draft), "draft=<$draft>")
            assertEquals(oracleQuery(draft), BotCommandPolicy.composerMenuQuery(draft), "draft=<$draft>")
        }
    }

    @Test
    fun filterCommandsRandomOracle() {
        val rnd = Random(35)
        repeat(200) {
            val items = List(rnd.nextInt(0, 31)) { randomItem(rnd) }
            val draft = randomDraft(rnd)
            assertEquals(oracleFilter(items, draft), BotCommandPolicy.filterCommands(items, draft), "draft=<$draft> items=${items.map { it.command }}")
        }
    }

    @Test
    fun insertCommandRandomOracle() {
        val rnd = Random(35)
        repeat(200) {
            val item = randomItem(rnd)
            val multiBot = rnd.nextBoolean()
            assertEquals(oracleInsert(item, multiBot), BotCommandPolicy.insertCommand(item, multiBot))
        }
    }

    @Test
    fun composerMenuPins() {
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/" + "a".repeat(63)))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("/" + "a".repeat(64)))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("  /" + "a".repeat(64)))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("  /" + "a".repeat(63)))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/"))
        assertEquals("", BotCommandPolicy.composerMenuQuery("/"))
        assertEquals("help", BotCommandPolicy.composerMenuQuery("  /HELP@alice arg"))
        assertEquals("help", BotCommandPolicy.composerMenuQuery("/help@alice"))
        assertEquals("help", BotCommandPolicy.composerMenuQuery("/help arg"))
        val items = (1..25).map {
            BotCommandItem(botId = "bot_$it", username = "u$it", name = "n$it", command = "help$it", description = "")
        }
        assertEquals(20, BotCommandPolicy.filterCommands(items, "/").size)
        assertEquals(20, BotCommandPolicy.filterCommands(items, "/help").size)
        assertEquals(20, BotCommandPolicy.filterCommands(items, "/HELP").size)
        val mixed = listOf(
            BotCommandItem(botId = "b1", username = "u", name = "n", command = "Hello", description = ""),
            BotCommandItem(botId = "b2", username = "u", name = "n", command = "hello", description = ""),
        )
        assertEquals(listOf(mixed[1]), BotCommandPolicy.filterCommands(mixed, "/hello"))
        assertEquals(emptyList(), BotCommandPolicy.filterCommands(mixed, "hello"))
        assertEquals("/hello@u ", BotCommandPolicy.insertCommand(mixed[1], true))
        assertEquals("/hello ", BotCommandPolicy.insertCommand(mixed[1], false))
    }
}
