package com.maodouchat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSurfaceGateControllerTest {

    @Test
    fun initialStateReadsFakeAndLockGates() {
        val controller = buildController(showFake = true, shouldLock = true)
        assertEquals(
            AppSurfaceGateController.State(showFakeChat = true, showAppLock = true),
            controller.initialState(),
        )
    }

    @Test
    fun onHostPausedSkipsAppLockBackgroundWhenLockVisible() {
        var appBg = 0
        var fakeBg = 0
        val controller = buildController(
            noteAppLockBackground = { appBg += 1 },
            noteFakeChatBackground = { fakeBg += 1 },
            appLockEnabled = true,
        )

        val secure = controller.onHostPaused(showAppLock = true)

        assertEquals(0, appBg)
        assertEquals(1, fakeBg)
        assertTrue(secure)
    }

    @Test
    fun onHostResumedPromotesFakeThenLock() {
        var markedUnlocked = 0
        val controller = buildController(
            showFake = true,
            shouldLock = true,
            markAppUnlocked = { markedUnlocked += 1 },
        )

        val next = controller.onHostResumed(
            AppSurfaceGateController.State(showFakeChat = false, showAppLock = false)
        )

        assertTrue(next.showFakeChat)
        assertTrue(next.showAppLock)
        assertEquals(0, markedUnlocked)
    }

    @Test
    fun onFakeChatUnlockedOpensAppLockWhenRequired() {
        var fakeUnlocked = 0
        var appUnlocked = 0
        val controller = buildController(
            shouldLock = true,
            markFakeUnlocked = { fakeUnlocked += 1 },
            markAppUnlocked = { appUnlocked += 1 },
        )

        val next = controller.onFakeChatUnlocked()

        assertEquals(1, fakeUnlocked)
        assertEquals(0, appUnlocked)
        assertFalse(next.showFakeChat)
        assertTrue(next.showAppLock)
    }

    @Test
    fun onAppLockUnlockedClearsOnlyLockGate() {
        var appUnlocked = 0
        val controller = buildController(markAppUnlocked = { appUnlocked += 1 })

        val next = controller.onAppLockUnlocked(
            AppSurfaceGateController.State(showFakeChat = true, showAppLock = true)
        )

        assertEquals(1, appUnlocked)
        assertTrue(next.showFakeChat)
        assertFalse(next.showAppLock)
    }

    private fun buildController(
        showFake: Boolean = false,
        shouldLock: Boolean = false,
        noteAppLockBackground: () -> Unit = {},
        noteFakeChatBackground: () -> Unit = {},
        markFakeUnlocked: () -> Unit = {},
        markAppUnlocked: () -> Unit = {},
        appLockEnabled: Boolean = false,
        fakeEnabled: Boolean = false,
        screenSecureEnabled: Boolean = false,
        onSecret: Boolean = false,
        hasActiveSecret: Boolean = false,
    ): AppSurfaceGateController =
        AppSurfaceGateController(
            shouldShowFake = { showFake },
            shouldLock = { shouldLock },
            noteAppLockBackground = noteAppLockBackground,
            noteFakeChatBackground = noteFakeChatBackground,
            markFakeUnlocked = markFakeUnlocked,
            markAppUnlocked = markAppUnlocked,
            isAppLockEnabled = { appLockEnabled },
            isFakeChatEnabled = { fakeEnabled },
            isScreenSecureEnabled = { screenSecureEnabled },
            onSecretChatSurface = { onSecret },
            hasActiveSecretSurface = { hasActiveSecret },
        )
}
