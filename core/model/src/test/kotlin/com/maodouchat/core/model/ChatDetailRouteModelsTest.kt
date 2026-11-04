package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
