package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChatDetailRouteModelsTest {

    @Test
    fun `particle actions are distinct`() {
        assertNotEquals(ParticleAction.DELETE, ParticleAction.REVOKE)
    }

    @Test
    fun `translation option carries wire value and label`() {
        val option = TranslationLanguageOption(wireValue = "zh", labelResource = 7)
        assertEquals("zh", option.wireValue)
        assertEquals(7, option.labelResource)
    }
}
