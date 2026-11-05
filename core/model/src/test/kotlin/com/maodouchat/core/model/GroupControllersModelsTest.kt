package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupControllersModelsTest {

    @Test
    fun `owned bot keeps fields and copy semantics`() {
        val bot = GroupOwnedBotUi("b1", "Bot", "bot")
        assertEquals("b1", bot.id)
        assertEquals("bot", bot.username)
        assertEquals("Other", bot.copy(name = "Other").name)
    }
}
