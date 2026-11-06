package com.maodouchat.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun messageTypeFromWireFallsBackToText() {
        assertEquals(MessageType.IMAGE, MessageType.fromWire("image"))
        assertEquals(MessageType.TEXT, MessageType.fromWire("unknown"))
        assertEquals(MessageType.TEXT, MessageType.fromWire(null))
        assertTrue(MessageType.SK_DIST.isHidden)
    }

    @Test
    fun messageMetaRoundTrips() {
        val meta = MessageMeta(
            mentions = listOf("u1"),
            replyToId = "m1",
            silent = true,
            inlineKeyboard = listOf(listOf(InlineKeyboardButton("yes", "y"))),
        )
        assertEquals(meta, json.decodeFromString<MessageMeta>(json.encodeToString(meta)))
    }

    @Test
    fun messageReactionDefaultsTimestamp() {
        val reaction = MessageReaction(userId = "u1", emoji = "👍")
        assertEquals("u1", reaction.userId)
        assertTrue(reaction.reactedAt > 0)
    }

    @Test
    fun locationPayloadDefaults() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5)
        assertEquals("", payload.label)
        assertTrue(!payload.live)
    }

    @Test
    fun messageDtoRoundTrips() {
        val decoded = json.decodeFromString<MessageDto>("""{"id":"m1","chatId":"c1","senderId":"u1","content":"hi","timestamp":42}""")
        assertEquals("TEXT", decoded.type)
        assertTrue(decoded.reactions.isEmpty())
        assertEquals(decoded, json.decodeFromString<MessageDto>(json.encodeToString(decoded)))
    }

    @Test
    fun chatDtoDerivedFlags() {
        val chat = ChatDto(id = "c1", isGroup = true)
        assertEquals("GROUP", chat.chatType)
        assertTrue(!chat.isChannel)
        assertTrue(ChatDto(id = "c2", chatType = "SECRET").isSecret)
        val invite = GroupInviteResponse(token = "t", payload = "p", chat = chat)
        assertEquals("t", invite.token)
        assertEquals(chat, invite.chat)
    }
}
