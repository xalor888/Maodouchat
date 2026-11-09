package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveSuggestionScoringTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

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

    @Test
    fun score_nullWhenNoMessages() {
        assertNull(ArchiveSuggestionScoring.score(chat(), emptyList(), now))
    }

    @Test
    fun score_nullForExcludedChats() {
        val messages = listOf(msg("m1", 40))
        assertNull(ArchiveSuggestionScoring.score(chat(archived = true), messages, now))
        assertNull(ArchiveSuggestionScoring.score(chat(pinnedAt = 1L), messages, now))
        assertNull(ArchiveSuggestionScoring.score(chat(markedUnread = true), messages, now))
        assertNull(ArchiveSuggestionScoring.score(chat(unreadCount = 2), messages, now))
    }

    @Test
    fun score_nullWhenRecentlyActive() {
        assertNull(ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", 1)), now))
        assertNull(ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", 13)), now))
    }

    @Test
    fun score_nullWhenScoreAtThreshold() {
        // 静置 14 天（+4）且近 30 天 30 条（+0）、无收尾信号：总分恰 4 分，不建议。
        val messages = (1..30).map { msg("m$it", 14) }
        assertNull(ArchiveSuggestionScoring.score(chat(), messages, now))
    }

    @Test
    fun score_idleChat() {
        // 静置 28 天（+8）、近 30 天 1 条（+14）、无收尾信号：22 分。
        val result = ArchiveSuggestionScoring.score(chat(), listOf(msg("m1", 28)), now)
        assertEquals(ArchiveSuggestionScoring.Score(score = 22, idleDays = 28, recent30 = 1), result)
    }

    @Test
    fun score_closingHintsAddThree() {
        // 静置 40 天（+10）、近 30 天 0 条（+15）、收尾占比 1.0（+3）：28 分。
        val messages = (1..5).map { msg("m$it", 40, "谢谢，已完成") }
        val result = ArchiveSuggestionScoring.score(chat(), messages, now)
        assertEquals(ArchiveSuggestionScoring.Score(score = 28, idleDays = 40, recent30 = 0), result)
    }

    @Test
    fun score_closingRatioBelowThresholdAddsNothing() {
        // 静置 40 天（+10）、近 30 天 0 条（+15）、收尾占比 0.3（不加分）：25 分。
        val messages = (1..10).map { msg("m$it", 40, if (it <= 3) "谢谢" else "hello") }
        val result = ArchiveSuggestionScoring.score(chat(), messages, now)
        assertEquals(ArchiveSuggestionScoring.Score(score = 25, idleDays = 40, recent30 = 0), result)
    }
}
