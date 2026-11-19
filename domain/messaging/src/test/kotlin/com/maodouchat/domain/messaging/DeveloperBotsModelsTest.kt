package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class DeveloperBotsModelsTest {

    @Test
    fun `bot ui keeps token prefix and defaults token once to empty`() {
        val bot = BotUi("1", "助手", "helper_bot", "tok_", "https://example.invalid/hook", true)
        assertEquals("", bot.tokenOnce)
        assertTrue(bot.enabled)
        assertEquals("helper_bot", bot.username)
    }
}
