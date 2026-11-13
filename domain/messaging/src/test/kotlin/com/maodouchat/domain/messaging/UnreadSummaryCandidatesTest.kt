package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals

class UnreadSummaryCandidatesTest {

    private fun message(
        id: String,
        timestamp: Long = 1_000L,
        senderId: String = "u2",
        type: MessageType = MessageType.TEXT,
        content: String = "hello $id",
        aiAssisted: Boolean = false,
    ) = Message(
        id = id,
        chatId = "c1",
        senderId = senderId,
        content = content,
        type = type,
        timestamp = timestamp,
        meta = MessageMeta(aiAssisted = aiAssisted),
    )

    @Test
    fun excludesOwnMessages() {
        val input = listOf(message("mine", senderId = "me"), message("theirs"))
        assertEquals(listOf("theirs"), selectUnreadSummaryCandidates(input, "me", 10).map { it.id })
    }

    @Test
    fun excludesNonTextTypes() {
        val input = listOf(
            message("text"),
            message("md", type = MessageType.MARKDOWN),
            message("img", type = MessageType.IMAGE),
            message("file", type = MessageType.FILE),
        )
        assertEquals(
            listOf("text", "md"),
            selectUnreadSummaryCandidates(input, "me", 10).map { it.id },
        )
    }

    @Test
    fun excludesAiAssistedAndBlankContent() {
        val input = listOf(
            message("ok"),
            message("assisted", aiAssisted = true),
            message("blank", content = "  "),
        )
        assertEquals(listOf("ok"), selectUnreadSummaryCandidates(input, "me", 10).map { it.id })
    }

    @Test
    fun sortsByTimestampAndTakesLast() {
        val input = listOf(
            message("m3", timestamp = 3_000L),
            message("m1", timestamp = 1_000L),
            message("m2", timestamp = 2_000L),
        )
        assertEquals(
            listOf("m2", "m3"),
            selectUnreadSummaryCandidates(input, "me", 2).map { it.id },
        )
    }

    @Test
    fun unreadCountBelowOneTakesOne() {
        val input = listOf(message("m1", timestamp = 1_000L), message("m2", timestamp = 2_000L))
        assertEquals(listOf("m2"), selectUnreadSummaryCandidates(input, "me", 0).map { it.id })
    }

    @Test
    fun unreadCountAboveMaxTakesMax() {
        val input = (0 until 50).map { i -> message("m$i", timestamp = i.toLong()) }
        assertEquals(48, selectUnreadSummaryCandidates(input, "me", 100).size, "上限 48 条")
    }

    @Test
    fun unreadKeyFormat() {
        assertEquals("c1:m1:m2:5", buildUnreadSummaryCacheKey("c1", "m1", "m2", 5))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList(), selectUnreadSummaryCandidates(emptyList(), "me", 10))
    }
}
