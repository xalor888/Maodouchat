package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ModerationRuleSettingsPolicyTest {

    @Test
    fun `hit threshold clamps to 1 to 10000`() {
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(0))
        assertEquals(10_000, ModerationRuleSettingsPolicy.clampHitThreshold(10_001))
        assertEquals(500, ModerationRuleSettingsPolicy.clampHitThreshold(500))
    }

    @Test
    fun `window converts minutes to millis clamped to 30 days`() {
        assertEquals(60_000L, ModerationRuleSettingsPolicy.windowMs(0))
        assertEquals(3_600_000L, ModerationRuleSettingsPolicy.windowMs(60))
        assertEquals(43_200L * 60_000L, ModerationRuleSettingsPolicy.windowMs(43_201))
        assertEquals(43_200L * 60_000L, ModerationRuleSettingsPolicy.windowMs(43_200))
    }
}
