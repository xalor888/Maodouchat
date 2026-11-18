package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupControllersModelsTest {

    @Test
    fun `owned bot keeps fields and copy semantics`() {
        val bot = GroupOwnedBotUi("b1", "Bot", "bot")
        assertEquals("b1", bot.id)
        assertEquals("bot", bot.username)
        assertEquals("Other", bot.copy(name = "Other").name)
    }
}
