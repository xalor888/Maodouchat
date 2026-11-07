package com.maodouchat.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellPureLogicTest {

    @Test
    fun surfaceGateInitialStateReadsGates() {
        val controller = gateController(showFake = true, shouldLock = true)
        assertEquals(
            AppSurfaceGateState(showFakeChat = true, showAppLock = true),
            controller.initialState(),
        )
    }

    @Test
    fun surfaceGatePausedNotesBackgroundWhenLockHidden() {
        var appBg = 0
        var fakeBg = 0
        val controller = gateController(
            noteAppLockBackground = { appBg += 1 },
            noteFakeChatBackground = { fakeBg += 1 },
            appLockEnabled = true,
        )
        assertTrue(controller.onHostPaused(showAppLock = false))
        assertEquals(1, appBg)
        assertEquals(1, fakeBg)
    }

    @Test
    fun surfaceGateFakeUnlockFallsBackToLock() {
        var fakeUnlocked = 0
        val controller = gateController(
            shouldLock = true,
            markFakeUnlocked = { fakeUnlocked += 1 },
        )
        val next = controller.onFakeChatUnlocked()
        assertEquals(1, fakeUnlocked)
        assertFalse(next.showFakeChat)
        assertTrue(next.showAppLock)
    }

    @Test
    fun foregroundLifecycleCreatedStartedStopped() {
        var foreground = false
        var cleared = 0
        var presence: Boolean? = null
        val controller = AppForegroundLifecycleController(
            setAppInForeground = { foreground = it },
            clearActiveChatSurface = { cleared += 1 },
            sendPresence = { presence = it },
        )
        controller.onHostCreated()
        assertTrue(foreground)
        controller.onHostStarted()
        assertTrue(foreground)
        assertEquals(true, presence)
        controller.onHostStopped()
        assertFalse(foreground)
        assertEquals(1, cleared)
        assertEquals(false, presence)
    }

    @Test
    fun callLockFlagTickStopDestroy() {
        var applied: Boolean? = null
        var needed = true
        val controller = CallLockScreenFlagController(
            flagsNeeded = { needed },
            applyFlags = { applied = it },
        )
        assertTrue(controller.onHostStartedTick())
        assertEquals(true, applied)
        needed = false
        controller.onHostStopped()
        assertEquals(false, applied)
        controller.onHostDestroyed()
        assertEquals(false, applied)
    }

    @Test
    fun secretWatermarkPolicyGatesOnSecretAndFlag() {
        assertTrue(SecretWatermarkPolicy.pageBlindWatermarkEnabled(true, true))
        assertFalse(SecretWatermarkPolicy.pageBlindWatermarkEnabled(false, true))
        assertFalse(SecretWatermarkPolicy.pageBlindWatermarkEnabled(true, false))
    }

    @Test
    fun secretPageWatermarkPayloadDeterministic() {
        val first = SecretPageWatermark.buildPayload("u1", "c1", "d1")
        val second = SecretPageWatermark.buildPayload("u1", "c1", "d1")
        assertEquals(FrequencyWatermark.PAYLOAD_BYTES, first.size)
        assertArrayEquals(first, second)
        assertEquals(12, FrequencyWatermark.decodePayloadHex(first).length)
        val other = SecretPageWatermark.buildPayload("u2", "c1", "d1")
        assertFalse(first.contentEquals(other))
    }

    private fun gateController(
        showFake: Boolean = false,
        shouldLock: Boolean = false,
        noteAppLockBackground: () -> Unit = {},
        noteFakeChatBackground: () -> Unit = {},
        markFakeUnlocked: () -> Unit = {},
        markAppUnlocked: () -> Unit = {},
        appLockEnabled: Boolean = false,
    ): AppSurfaceGateController =
        AppSurfaceGateController(
            shouldShowFake = { showFake },
            shouldLock = { shouldLock },
            noteAppLockBackground = noteAppLockBackground,
            noteFakeChatBackground = noteFakeChatBackground,
            markFakeUnlocked = markFakeUnlocked,
            markAppUnlocked = markAppUnlocked,
            isAppLockEnabled = { appLockEnabled },
            isFakeChatEnabled = { false },
            isScreenSecureEnabled = { false },
            onSecretChatSurface = { false },
            hasActiveSecretSurface = { false },
        )
}
