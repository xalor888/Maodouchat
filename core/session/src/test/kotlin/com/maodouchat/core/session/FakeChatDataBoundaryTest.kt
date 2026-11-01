package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeChatDataBoundaryTest {

    @Test
    fun fakeMode_blocksRealDataAccess() {
        assertFalse(FakeChatDataBoundary.mayAccessRealData(isFakeModeActive = true))
        assertTrue(FakeChatDataBoundary.mayAccessRealData(isFakeModeActive = false))
    }

    @Test
    fun fakeMode_notificationDesensitized() {
        val (title, body) = FakeChatDataBoundary.sanitizeNotification(
            isFakeModeActive = true,
            realTitle = "Secret Contact",
            realBody = "Top secret message 123"
        )
        assertEquals("系统更新", title)
        assertEquals("您有一条新的系统服务提醒", body)
    }

    @Test
    fun mockChatId_prefixRoundTrip() {
        val id = FakeChatDataBoundary.createMockChatId("support_bot")
        assertEquals("fake_mock_support_bot", id)
        assertTrue(FakeChatDataBoundary.isMockChatId(id))
        assertFalse(FakeChatDataBoundary.isMockChatId("chat_real_12345"))
        assertTrue(FakeChatThreatModel.THREAT_COERCION.isNotBlank())
    }
}
