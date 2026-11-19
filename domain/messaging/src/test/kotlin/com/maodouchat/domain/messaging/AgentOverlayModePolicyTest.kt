package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentOverlayMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentOverlayModePolicyTest {

    @Test
    fun `parses known overlay modes`() {
        assertEquals(AgentOverlayMode.DISABLED, AgentOverlayModePolicy.parse("DISABLED"))
        assertEquals(AgentOverlayMode.FOREGROUND_ONLY, AgentOverlayModePolicy.parse("FOREGROUND_ONLY"))
        assertEquals(AgentOverlayMode.ALWAYS, AgentOverlayModePolicy.parse("ALWAYS"))
    }

    @Test
    fun `unknown null and blank values fall back to disabled`() {
        assertEquals(AgentOverlayMode.DISABLED, AgentOverlayModePolicy.parse("SOMETHING_ELSE"))
        assertEquals(AgentOverlayMode.DISABLED, AgentOverlayModePolicy.parse(null))
        assertEquals(AgentOverlayMode.DISABLED, AgentOverlayModePolicy.parse(""))
        assertEquals(AgentOverlayMode.DISABLED, AgentOverlayModePolicy.parse("disabled"))
    }
}
