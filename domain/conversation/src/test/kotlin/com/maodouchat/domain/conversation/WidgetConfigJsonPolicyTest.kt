package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetConfigJsonPolicyTest {

    @Test
    fun `round trip preserves all fields`() {
        val config = WidgetConfig(chatIds = listOf("a", "b"), showUnreadBadge = false, compact = true)
        assertEquals(config, WidgetConfigJsonPolicy.decodeConfig(WidgetConfigJsonPolicy.encodeConfig(config)))
    }

    @Test
    fun `partial json fills defaults`() {
        assertEquals(
            WidgetConfig(chatIds = listOf("a"), showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":["a"]}""")
        )
        assertEquals(
            WidgetConfig(chatIds = emptyList(), showUnreadBadge = false, compact = true),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":false,"compact":true}""")
        )
    }

    @Test
    fun `chat ids truncate to six`() {
        val decoded = WidgetConfigJsonPolicy.decodeConfig(
            """{"chatIds":["1","2","3","4","5","6","7","8"]}"""
        )
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), decoded.chatIds)
    }

    @Test
    fun `malformed json decodes to the default config`() {
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("not json"))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig(""))
    }
}
