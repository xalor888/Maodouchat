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

    @Test
    fun missingEventYieldsEmptyEventButKeepsChatId() {
        val parsed = parseGroupPlayUpdate("""{"data":{"chatId":"g7"}}""")
        assertEquals("", parsed.event)
        assertEquals("g7", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g7"))
    }

    // contentOrNull 只在 JSON null 时给 null，数字/布尔会按字面转成字符串（"42"）——与生产分支同行为。
    @Test
    fun nonStringEventIsStringified() {
        val parsed = parseGroupPlayUpdate("""{"event":42,"data":{"chatId":"g1"}}""")
        assertEquals("42", parsed.event)
        assertEquals("g1", parsed.chatId)
    }

    @Test
    fun nonStringChatIdIsStringified() {
        val parsed = parseGroupPlayUpdate("""{"event":"pk_updated","data":{"chatId":42}}""")
        assertEquals("42", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun explicitNullEventAndChatIdYieldEmpty() {
        val parsed = parseGroupPlayUpdate("""{"event":null,"data":{"chatId":null}}""")
        assertEquals("", parsed.event)
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun emptyDataObjectYieldsBlankChatId() {
        val parsed = parseGroupPlayUpdate("""{"event":"chain_updated","data":{}}""")
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun extraTopLevelKeysAreIgnored() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"checkin_updated","data":{"chatId":"g2"},"traceId":"t1","serverTs":99}"""
        )
        assertEquals("g2", parsed.chatId)
        assertEquals("checkin_updated", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g2"))
    }

    @Test
    fun unknownEventStillParsesAndRefreshes() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"something_new","data":{"chatId":"g3"}}"""
        )
        assertEquals("something_new", parsed.event)
        assertEquals("g3", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g3"))
    }

    @Test
    fun whitespacePaddedChatIdDoesNotMatchScreen() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":" g1 "}}"""
        )
        assertEquals(" g1 ", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun blankScreenIdNeverRefreshes() {
        assertFalse(shouldRefreshScreen("g1", ""))
        assertFalse(shouldRefreshScreen("", ""))
    }

    @Test
    fun explicitEmptyChatIdYieldsNoRefresh() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"chain_updated","data":{"chatId":""}}"""
        )
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun payloadJsonPreservesOriginalPayload() {
        val payload = """{"event":"pk_closed","data":{"chatId":"g4"}}"""
        val parsed = parseGroupPlayUpdate(payload)
        assertEquals(payload, parsed.payloadJson)
    }

    @Test
    fun upperCaseEventStillRefreshesOnChatIdMatch() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"PK_UPDATED","data":{"chatId":"g1"}}"""
        )
        assertEquals("PK_UPDATED", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun unicodeChatIdMatchesSameScreen() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"群组1"}}"""
        )
        assertTrue(shouldRefreshScreen(parsed.chatId, "群组1"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "群组2"))
    }

    @Test
    fun explicitBlankEventStillRefreshesOnChatId() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"","data":{"chatId":"g1"}}"""
        )
        assertEquals("", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun numericStringChatIdMatchesSameScreen() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":42}}"""
        )
        assertEquals("42", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "42"))
    }

    @Test
    fun whitespaceOnlyChatIdNeverRefreshes() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"   "}}"""
        )
        assertEquals("   ", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "   "))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun booleanChatIdIsStringified() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":true}}"""
        )
        assertEquals("true", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "true"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun escapedEventNamePreserved() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_\"updated\"","data":{"chatId":"g1"}}"""
        )
        assertEquals("pk_\"updated\"", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun newlineAndTabChatIdPreservedVerbatim() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"g\n1\t"}}"""
        )
        assertEquals("g\n1\t", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g\n1\t"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun topLevelChatIdDecoyIgnored() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","chatId":"g9","data":{}}"""
        )
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g9"))
    }

    @Test
    fun emptyObjectYieldsEmptyEventAndChatId() {
        val parsed = parseGroupPlayUpdate("""{}""")
        assertEquals("", parsed.event)
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun negativeNumericChatIdIsStringified() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":-7}}"""
        )
        assertEquals("-7", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "-7"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun floatChatIdIsStringified() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":1.5}}"""
        )
        assertEquals("1.5", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "1.5"))
    }

    @Test
    fun spacedEventNameIsPreservedVerbatim() {
        val parsed = parseGroupPlayUpdate(
            """{"event":" pk_updated ","data":{"chatId":"g1"}}"""
        )
        assertEquals(" pk_updated ", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun nestedDataExtrasAreIgnored() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"checkin_updated","data":{"chatId":"g5","meta":{"a":1},"tags":["x"]}}"""
        )
        assertEquals("g5", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g5"))
    }

    @Test
    fun nullEventOnlyStillRefreshesOnChatId() {
        val parsed = parseGroupPlayUpdate(
            """{"event":null,"data":{"chatId":"g1"}}"""
        )
        assertEquals("", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun emojiChatIdMatchesSameScreen() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"群组🎉"}}"""
        )
        assertEquals("群组🎉", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "群组🎉"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "群组"))
    }

    @Test
    fun eventWithColonAndSlashIsPreservedVerbatim() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk:updated/v2","data":{"chatId":"g1"}}"""
        )
        assertEquals("pk:updated/v2", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun veryLongChatIdMatchesOnlyExactly() {
        val longId = "g" + "1".repeat(200)
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"$longId"}}"""
        )
        assertEquals(longId, parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, longId))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g111"))
    }

    @Test
    fun reversedKeyOrderStillParses() {
        val parsed = parseGroupPlayUpdate(
            """{"traceId":"t1","data":{"chatId":"g8"},"event":"pk_updated"}"""
        )
        assertEquals("g8", parsed.chatId)
        assertEquals("pk_updated", parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g8"))
    }

    @Test
    fun chatIdMatchIsCaseSensitive() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"G1"}}"""
        )
        assertTrue(shouldRefreshScreen(parsed.chatId, "G1"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun chatIdWithLeadingZerosMatchesExactly() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"007"}}"""
        )
        assertEquals("007", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "007"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "7"))
    }

    @Test
    fun chatIdWithSpaceInsideMatchesExactly() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"chatId":"g 1"}}"""
        )
        assertEquals("g 1", parsed.chatId)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g 1"))
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun nestedMetaChatIdDecoyIgnored() {
        val parsed = parseGroupPlayUpdate(
            """{"event":"pk_updated","data":{"meta":{"chatId":"g9"}}}"""
        )
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g9"))
    }

    @Test
    fun veryLongEventNamePreservedVerbatim() {
        val longEvent = "e".repeat(200)
        val parsed = parseGroupPlayUpdate(
            """{"event":"$longEvent","data":{"chatId":"g1"}}"""
        )
        assertEquals(longEvent, parsed.event)
        assertTrue(shouldRefreshScreen(parsed.chatId, "g1"))
    }

    @Test
    fun nullEventWithoutDataYieldsNoRefresh() {
        val parsed = parseGroupPlayUpdate("""{"event":null}""")
        assertEquals("", parsed.event)
        assertEquals("", parsed.chatId)
        assertFalse(shouldRefreshScreen(parsed.chatId, "g1"))
    }

}
