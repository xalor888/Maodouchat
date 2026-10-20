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

        assertTrue(
            FakeChatDataBoundary.mayHideLauncherIcon(
                hasPin = true,
                isSecretCodeReceiverRegistered = true
            )
        )
    }

    @Test
    fun sanitizeNotification_customMockStrings_honoredOnlyInFakeMode() {
        val (titleFake, bodyFake) = FakeChatDataBoundary.sanitizeNotification(
            isFakeModeActive = true,
            realTitle = "Secret Contact",
            realBody = "Top secret message 123",
            mockTitle = "更新完成",
            mockBody = "系统已是最新版本"
        )
        assertEquals("更新完成", titleFake)
        assertEquals("系统已是最新版本", bodyFake)

        val (titleReal, bodyReal) = FakeChatDataBoundary.sanitizeNotification(
            isFakeModeActive = false,
            realTitle = "Secret Contact",
            realBody = "Top secret message 123",
            mockTitle = "更新完成",
            mockBody = "系统已是最新版本"
        )
        assertEquals("Secret Contact", titleReal)
        assertEquals("Top secret message 123", bodyReal)
    }

    @Test
    fun sanitizeNotification_realMode_passesThroughEmptyContent() {
        val (title, body) = FakeChatDataBoundary.sanitizeNotification(
            isFakeModeActive = false,
            realTitle = "",
            realBody = ""
        )
        assertEquals("", title)
        assertEquals("", body)
    }

    @Test
    fun mayHideLauncherIcon_dangerousOverride_bypassesReceiverButNotPin() {
        assertTrue(
            FakeChatDataBoundary.mayHideLauncherIcon(
                hasPin = true,
                isSecretCodeReceiverRegistered = false,
                allowDangerousHiding = true
            )
        )
        assertFalse(
            FakeChatDataBoundary.mayHideLauncherIcon(
                hasPin = false,
                isSecretCodeReceiverRegistered = false,
                allowDangerousHiding = true
            )
        )
    }

    @Test
    fun isMockChatId_rejectsNearMissVariants() {
        assertFalse(FakeChatDataBoundary.isMockChatId(""))
        assertFalse(FakeChatDataBoundary.isMockChatId("fake_mock"))
        assertFalse(FakeChatDataBoundary.isMockChatId("xfake_mock_bot"))
        assertTrue(FakeChatDataBoundary.isMockChatId("fake_mock_"))
    }

    @Test
    fun threatModelConstants_areDistinctAndNonBlank() {
        val constants = listOf(
            FakeChatThreatModel.THREAT_COERCION,
            FakeChatThreatModel.THREAT_LAUNCHER_LOCKOUT,
            FakeChatThreatModel.THREAT_DATA_LEAKAGE,
            FakeChatThreatModel.THREAT_PANIC_WIPE
        )
        assertTrue(constants.all { it.isNotBlank() })
        assertEquals(constants.size, constants.toSet().size)
    }

    @Test
    fun mockChatId_prefixesAndChecksCorrectly() {
        val id = FakeChatDataBoundary.createMockChatId("support_bot")
        assertEquals("fake_mock_support_bot", id)
        assertTrue(FakeChatDataBoundary.isMockChatId(id))
        assertFalse(FakeChatDataBoundary.isMockChatId("chat_real_12345"))
    }
}
