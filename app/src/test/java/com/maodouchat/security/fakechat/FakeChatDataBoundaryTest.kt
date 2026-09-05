package com.maodouchat.security.fakechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeChatDataBoundaryTest {

    @Test
    fun mayAccessRealData_blocksAccessWhenFakeModeActive() {
        assertFalse(FakeChatDataBoundary.mayAccessRealData(isFakeModeActive = true))
        assertTrue(FakeChatDataBoundary.mayAccessRealData(isFakeModeActive = false))
    }

    @Test
    fun sanitizeNotification_desensitizesContentInFakeMode() {
        val (titleFake, bodyFake) = FakeChatDataBoundary.sanitizeNotification(
            isFakeModeActive = true,
            realTitle = "Secret Contact",
            realBody = "Top secret message 123"
        )
        assertEquals("系统更新", titleFake)
        assertEquals("您有一条新的系统服务提醒", bodyFake)

        val (titleReal, bodyReal) = FakeChatDataBoundary.sanitizeNotification(
            isFakeModeActive = false,
            realTitle = "Secret Contact",
            realBody = "Top secret message 123"
        )
        assertEquals("Secret Contact", titleReal)
        assertEquals("Top secret message 123", bodyReal)
    }

    @Test
    fun mayHideLauncherIcon_preventsDeadlock() {
        // No PIN -> Cannot hide
        assertFalse(
            FakeChatDataBoundary.mayHideLauncherIcon(
                hasPin = false,
                isSecretCodeReceiverRegistered = true
            )
        )

        // Has PIN but receiver not registered -> Cannot hide (prevents permanent lockout)
        assertFalse(
            FakeChatDataBoundary.mayHideLauncherIcon(
                hasPin = true,
                isSecretCodeReceiverRegistered = false
            )
        )

        // Has PIN and receiver registered -> Allowed
        assertTrue(
            FakeChatDataBoundary.mayHideLauncherIcon(
                hasPin = true,
                isSecretCodeReceiverRegistered = true
            )
        )
    }

    @Test
    fun mockChatId_prefixesAndChecksCorrectly() {
        val id = FakeChatDataBoundary.createMockChatId("support_bot")
        assertEquals("fake_mock_support_bot", id)
        assertTrue(FakeChatDataBoundary.isMockChatId(id))
        assertFalse(FakeChatDataBoundary.isMockChatId("chat_real_12345"))
    }
}
