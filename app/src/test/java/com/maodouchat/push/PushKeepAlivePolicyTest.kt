package com.maodouchat.push

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushKeepAlivePolicyTest {

    @Test
    fun offAndUnknownModesAreDisabled() {
        assertFalse(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_OFF))
        assertFalse(PushKeepAlivePolicy.isEnabled("bogus"))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_FOREGROUND))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_MEDIA))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_CALL))
    }

    @Test
    fun serviceRequiresEnabledModeAndToken() {
        assertFalse(
            PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.MODE_FOREGROUND, hasToken = false)
        )
        assertFalse(
            PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.MODE_OFF, hasToken = true)
        )
        assertTrue(
            PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.MODE_FOREGROUND, hasToken = true)
        )
        assertFalse(
            PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_CALL, hasToken = false)
        )
        assertTrue(
            PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_CALL, hasToken = true)
        )
    }
}
