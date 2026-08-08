package com.maodouchat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindWatermarkLabelTest {
    // 固定时间戳，避免测试依赖当前时间；用同一格式化器计算期望后缀，时区无关。
    private val fixedTs = 1_700_000_000_000L
    private val timeSuffix = "·" + formatWatermarkTime(fixedTs)

    @Test
    fun `builds compact label with user and chat fragments`() {
        val label = buildBlindWatermarkLabel(
            userId = "user-abcdef012345",
            chatId = "chat-9876543210",
            deviceHint = "deadbeef",
            timestampMs = fixedTs
        )
        // shortenId keeps alphanumerics then takes first 8 / 4 chars; 时间戳用于取证归属。
        assertEquals("MC·userabcd·chat9876·dead$timeSuffix", label)
    }

    @Test
    fun `falls back when ids blank`() {
        assertEquals("MC·anon·chat$timeSuffix", buildBlindWatermarkLabel(null, null, null, fixedTs))
        assertEquals("MC·anon·chat$timeSuffix", buildBlindWatermarkLabel("", "", "", fixedTs))
    }

    @Test
    fun `omits device when blank`() {
        val label = buildBlindWatermarkLabel("alice", "room1", null, fixedTs)
        assertEquals("MC·alice·room1$timeSuffix", label)
        assertFalse(label.endsWith("·"))
        assertTrue(label.startsWith("MC·"))
    }
}
