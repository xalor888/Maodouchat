package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ArchiveSuggestionScoringFuzzTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    // 生产源码里的收尾信号表逐字抄录，只供 oracle 重算。
    private val closingHints = listOf(
        "谢谢", "感谢", "已结束", "已完成", "搞定", "解决了", "结束",
        "收到", "了解了", "不再", "退群", "暂停", "取消活动", "优惠已过期",
        "thanks", "thank you", "done", "closed", "completed", "resolved",
    )

    private val plainContents = listOf(
        "hello", "今天天气不错", "开会时间改到三点", "项目进度正常",
        "午饭吃什么", "", " ", "ok", "明天见", "好的",
    )

    private val entityContents = listOf(
        "&#35874;&#35874;", "谢谢&amp;再见", "a&lt;b", "&#10;", "&nbsp;谢谢",
        "prefix<meta>{\"k\":1}</meta>", "谢谢<meta>junk", "&#0;谢谢",
        "&#1114112;谢谢", "&#x8c22;&#x8c22;", "&#X8C22;",
    )

    private fun chat(
        archived: Boolean = false,
        pinnedAt: Long = 0L,
        markedUnread: Boolean = false,
        unreadCount: Int = 0,
    ) = Chat(
        id = "c1",
        archived = archived,
        pinnedAt = pinnedAt,
        markedUnread = markedUnread,
        unreadCount = unreadCount,
    )

    private fun msg(id: String, daysAgo: Long, content: String = "hello") =
        Message(
            id = id,
            chatId = "c1",
            senderId = "u1",
            content = content,
            timestamp = now - daysAgo * day,
        )

    // Message.parsedContent 的逐字复刻：先剥末尾 <meta> 块，再反转义 HTML 实体。
    private fun expectedParsedContent(raw: String): String {
        val idx = raw.lastIndexOf("<meta>")
        val body = if (idx < 0) raw else raw.substring(0, idx)
        return unescapeHtmlEntities(body)
    }

    private fun unescapeHtmlEntities(raw: String): String {
        if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
        var out = raw
        out = Regex("&#(\\d{1,7});").replace(out) { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        out = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE).replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        return out
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    // 打分公式的独立重算：与生产实现逐行对照的另一份推导，只用作期望值。
    private fun expectedScore(chat: Chat, messages: List<Message>, nowMs: Long): ArchiveSuggestionScoring.Score? {
        if (messages.isEmpty()) return null
        if (chat.archived || chat.pinnedAt > 0L || chat.markedUnread || chat.unreadCount > 0) return null
        val lastActive = messages.maxOfOrNull { it.timestamp } ?: chat.lastMessageTime
        if (nowMs - lastActive < 14L * day) return null

        var score = 0
        val recent30 = messages.count { nowMs - it.timestamp <= 30L * day }
        val idleDays = ((nowMs - lastActive).coerceAtLeast(0L)) / day
        score += ((idleDays / 7).coerceAtMost(6) * 2).toInt()
        score += (30 - recent30.coerceAtMost(30)) / 2

        var closingHintsFound = 0
        var totalHints = 0
        messages.takeLast(40).forEach { message ->
            val text = expectedParsedContent(message.content)
            totalHints++
            if (closingHints.any { text.contains(it) }) closingHintsFound++
        }
        if (totalHints > 0) {
            val ratio = closingHintsFound.toDouble() / totalHints
            if (ratio >= 0.4) score += 3
        }
        if (score <= 4) return null
        return ArchiveSuggestionScoring.Score(score, idleDays, recent30)
    }

    private fun randomContent(random: Random): String =
        when (random.nextInt(10)) {
            in 0..4 -> plainContents[random.nextInt(plainContents.size)]
            in 5..7 -> closingHints[random.nextInt(closingHints.size)]
            8 -> closingHints[random.nextInt(closingHints.size)] + "，" + plainContents[random.nextInt(plainContents.size)]
            else -> entityContents[random.nextInt(entityContents.size)]
        }

    private fun randomChat(random: Random): Chat {
        val flag = random.nextInt(10)
        return chat(
            archived = flag == 0,
            pinnedAt = if (flag == 1) random.nextLong(1, 100000) else 0L,
            markedUnread = flag == 2,
            unreadCount = if (flag == 3) random.nextInt(1, 3) else 0,
        )
    }

    private fun randomMessages(random: Random, count: Int): List<Message> =
        List(count) { i ->
            val daysAgo = when (random.nextInt(20)) {
                0 -> -1L
                1 -> 13L
                2 -> 14L
                3 -> 30L
                4 -> 40L
                else -> random.nextLong(0, 121)
            }
            msg("m$i", daysAgo, randomContent(random))
        }

    @Test
    fun score_randomMatchesIndependentRecomputation() {
        val random = Random(93102)
        repeat(300) {
            val c = randomChat(random)
            val messages = randomMessages(random, random.nextInt(0, 51))
            assertEquals(expectedScore(c, messages, now), ArchiveSuggestionScoring.score(c, messages, now))
        }
    }

    @Test
    fun score_nonNullScoresStayWithinDocumentedRanges() {
        val random = Random(93112)
        repeat(300) {
            val c = randomChat(random)
            val messages = randomMessages(random, random.nextInt(0, 51))
            val result = ArchiveSuggestionScoring.score(c, messages, now)
            if (result != null) {
                assertTrue(result.score in 5..30)
                assertTrue(result.idleDays >= 14)
                assertEquals(messages.count { now - it.timestamp <= 30L * day }, result.recent30)
                assertEquals(expectedScore(c, messages, now), result)
            }
        }
    }

    @Test
    fun score_pinnedSilenceAndScoreThresholds() {
        // 静置 14 天（+4）且近 30 天 30 条（+0）：总分恰 4，不建议。
        assertNull(ArchiveSuggestionScoring.score(chat(), (1..30).map { msg("m$it", 14) }, now))
        // 同样静置 14 天，28 条（+1）：总分恰 5，建议。
        assertEquals(
            ArchiveSuggestionScoring.Score(score = 5, idleDays = 14, recent30 = 28),
            ArchiveSuggestionScoring.score(chat(), (1..28).map { msg("m$it", 14) }, now),
        )
        // 静置 21 天（+6）、近 30 天 0 条（+15）：21 分。
        assertEquals(
            ArchiveSuggestionScoring.Score(score = 21, idleDays = 21, recent30 = 0),
            ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", 21)), now),
        )
        // 13 天内活跃、未来消息、空列表：一律 null。
        assertNull(ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", 13)), now))
        assertNull(ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", -1)), now))
        assertNull(ArchiveSuggestionScoring.score(chat(), emptyList(), now))
    }

    @Test
    fun score_pinnedClosingRatioBoundaryAtZeroPointFour() {
        // 静置 40 天（+10）、近 30 天 0 条（+15）：10 条里 4 条收尾（占比恰 0.4）加 3 分得 28。
        val withBonus = (1..10).map { msg("m$it", 40, if (it <= 4) "谢谢" else "hello") }
        assertEquals(
            ArchiveSuggestionScoring.Score(score = 28, idleDays = 40, recent30 = 0),
            ArchiveSuggestionScoring.score(chat(), withBonus, now),
        )
        // 3 条收尾（占比 0.3）不加分，得 25。
        val withoutBonus = (1..10).map { msg("m$it", 40, if (it <= 3) "谢谢" else "hello") }
        assertEquals(
            ArchiveSuggestionScoring.Score(score = 25, idleDays = 40, recent30 = 0),
            ArchiveSuggestionScoring.score(chat(), withoutBonus, now),
        )
    }

    @Test
    fun score_pinnedTailWindowCoversOnlyLastFortyMessages() {
        // 50 条里前 10 条收尾、后 40 条普通：尾部 40 条占比为 0，不加分。
        val headHints = (1..50).map { msg("m$it", 40, if (it <= 10) "谢谢" else "hello") }
        assertEquals(
            ArchiveSuggestionScoring.Score(score = 25, idleDays = 40, recent30 = 0),
            ArchiveSuggestionScoring.score(chat(), headHints, now),
        )
        // 反过来：前 10 条普通、后 40 条收尾：尾部占比 1.0，加 3 分。
        val tailHints = (1..50).map { msg("m$it", 40, if (it <= 10) "hello" else "谢谢") }
        assertEquals(
            ArchiveSuggestionScoring.Score(score = 28, idleDays = 40, recent30 = 0),
            ArchiveSuggestionScoring.score(chat(), tailHints, now),
        )
    }

    @Test
    fun score_pinnedMetaTagAndEntitiesAreStrippedBeforeHintMatching() {
        // 单条 40 天前的消息：有收尾信号得 28，无信号得 25。
        fun scoreOf(content: String) =
            ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", 40, content)), now)?.score
        assertEquals(28, scoreOf("谢谢<meta>{\"a\":1}</meta>"))
        assertEquals(28, scoreOf("&#35874;&#35874;"))
        assertEquals(28, scoreOf("&#x8c22;&#x8c22;"))
        assertEquals(28, scoreOf("&nbsp;谢谢"))
        assertEquals(28, scoreOf("&#0;谢谢"))
        assertEquals(28, scoreOf("&#1114112;谢谢"))
        assertEquals(25, scoreOf("谢&amp;谢"))
        assertEquals(25, scoreOf("a&lt;b"))
        assertEquals(25, scoreOf("prefix<meta>{\"k\":1}</meta>"))
    }
}
