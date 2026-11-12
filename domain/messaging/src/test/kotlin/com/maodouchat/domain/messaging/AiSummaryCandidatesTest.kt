package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiSummaryCandidatesTest {

    private val now = 1_794_465_600_000L
    private val day = 24L * 60L * 60L * 1_000L

    private fun message(
        id: String,
        timestamp: Long,
        content: String = "hello $id",
        aiAssisted: Boolean = false,
    ) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = content,
        timestamp = timestamp,
        meta = MessageMeta(aiAssisted = aiAssisted),
    )

    @Test
    fun `recent returns latest 24 sorted by timestamp`() {
        val input = (0 until 30).map { i -> message("m$i", now - (29 - i) * 1_000L) }.reversed()
        val result = summaryCandidates(AiSummaryScope.RECENT, emptyList(), input, now)
        assertEquals(24, result.size)
        assertEquals((6 until 30).map { "m$it" }, result.map { it.id })
    }

    @Test
    fun `today keeps only messages from today`() {
        val input = listOf(
            message("now", now),
            message("old", now - 8 * day),
        )
        val result = summaryCandidates(AiSummaryScope.TODAY, emptyList(), input, now)
        assertEquals(listOf("now"), result.map { it.id })
    }

    @Test
    fun `seven days keeps messages within a week`() {
        val input = listOf(
            message("in", now - 6 * day),
            message("out", now - 8 * day),
        )
        val result = summaryCandidates(AiSummaryScope.SEVEN_DAYS, emptyList(), input, now)
        assertEquals(listOf("in"), result.map { it.id })
    }

    @Test
    fun `thirty days keeps messages within a month`() {
        val input = listOf(
            message("in", now - 29 * day),
            message("out", now - 31 * day),
        )
        val result = summaryCandidates(AiSummaryScope.THIRTY_DAYS, emptyList(), input, now)
        assertEquals(listOf("in"), result.map { it.id })
    }

    @Test
    fun `search results matches ids and ignores blank ids`() {
        val input = listOf(
            message("m1", now),
            message("m2", now),
            message("m3", now),
        )
        val result = summaryCandidates(
            AiSummaryScope.SEARCH_RESULTS,
            listOf("m1", "  ", "m3"),
            input,
            now,
        )
        assertEquals(listOf("m1", "m3"), result.map { it.id })
    }

    @Test
    fun `unread scope always returns empty`() {
        val input = listOf(message("m1", now))
        assertTrue(summaryCandidates(AiSummaryScope.UNREAD, emptyList(), input, now).isEmpty())
    }

    @Test
    fun `ai assisted and blank messages are excluded`() {
        val input = listOf(
            message("ai", now, aiAssisted = true),
            message("blank", now, content = "   "),
            message("ok", now),
        )
        val result = summaryCandidates(AiSummaryScope.RECENT, emptyList(), input, now)
        assertEquals(listOf("ok"), result.map { it.id })
    }

    @Test
    fun `non recent scopes cap at max summary messages`() {
        val input = (0 until 50).map { i -> message("m$i", now - i * 1_000L) }
        val result = summaryCandidates(AiSummaryScope.SEVEN_DAYS, emptyList(), input, now)
        assertEquals(48, result.size)
    }
}
