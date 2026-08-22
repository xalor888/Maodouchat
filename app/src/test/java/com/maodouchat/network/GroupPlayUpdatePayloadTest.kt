package com.maodouchat.network

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XAL-41：锁定 GROUP_PLAY_UPDATE 客户端解析路径。
 * WebSocketClient 读 `{event, data.chatId}`；屏幕仅在 `event.chatId == viewModel.chatId` 时刷新。
 * 不实例化 WebSocketClient，只锁同一 JSON 路径。
 */
class GroupPlayUpdatePayloadTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private data class GroupPlayUpdate(
        val chatId: String,
        val event: String,
        val payloadJson: String
    )

    /** 与 [com.maodouchat.network.WebSocketClient] GROUP_PLAY_UPDATE 分支同一路径。 */
    private fun parseGroupPlayUpdate(payload: String): GroupPlayUpdate {
        val obj = json.parseToJsonElement(payload).jsonObject
        val event = obj["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val chatId = obj["data"]?.jsonObject?.get("chatId")
            ?.jsonPrimitive?.contentOrNull.orEmpty()
        return GroupPlayUpdate(chatId = chatId, event = event, payloadJson = payload)
    }

    private fun shouldRefreshScreen(eventChatId: String, screenChatId: String): Boolean =
        eventChatId.isNotBlank() && eventChatId == screenChatId

    @Test
    fun parsesEventAndChatIdFromWrappedPayload() {
        val payload = """
            {
              "event": "checkin_updated",
              "data": {
                "chatId": "g1",
                "userId": "u2",
                "alreadyCheckedIn": false,
                "todayCount": 3
              }
            }
        """.trimIndent()
        val parsed = parseGroupPlayUpdate(payload)
        assertEquals("g1", parsed.chatId)
        assertEquals("checkin_updated", parsed.event)
        assertTrue(parsed.payloadJson.contains("alreadyCheckedIn"))
    }

    @Test
    fun knownPlayEventsKeepChatId() {
        listOf(
            "checkin_updated",
            "chain_created",
            "chain_updated",
            "pk_created",
            "pk_updated",
            "pk_closed"
        ).forEach { event ->
            val parsed = parseGroupPlayUpdate(
                """{"event":"$event","data":{"chatId":"g-play","id":"x1"}}"""
            )
            assertEquals(event, parsed.chatId.let { event to parsed.event }.second)
            assertEquals("g-play", parsed.chatId)
        }
    }

    @Test
    fun refreshOnlyWhenEventChatMatchesOpenScreen() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"g1","myChoice":"left"}}"""
        )
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g2"))
        assertFalse(shouldRefreshScreen("", "g1"))
    }

    @Test
    fun missingDataYieldsBlankChatIdAndDoesNotRefresh() {
        val noData = parseGroupPlayUpdate("""{"event":"chain_updated"}""")
        assertEquals("", noData.chatId)
        assertEquals("chain_updated", noData.event)
        assertFalse(shouldRefreshScreen(noData.chatId, "g1"))

        val noChatId = parseGroupPlayUpdate("""{"event":"pk_created","data":{"id":"pk1"}}""")
        assertEquals("", noChatId.chatId)
        assertFalse(shouldRefreshScreen(noChatId.chatId, "g1"))
    }
}
