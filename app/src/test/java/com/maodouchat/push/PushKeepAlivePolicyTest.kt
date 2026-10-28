package com.maodouchat.push

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun legacyMediaAndCallNormalizeToForeground() {
        assertEquals(
            PushKeepAliveModeStore.MODE_FOREGROUND,
            PushKeepAlivePolicy.effectiveMode(PushKeepAliveModeStore.MODE_MEDIA),
        )
        assertEquals(
            PushKeepAliveModeStore.MODE_FOREGROUND,
            PushKeepAlivePolicy.effectiveMode(PushKeepAliveModeStore.MODE_CALL),
        )
        assertEquals(
            PushKeepAliveModeStore.MODE_FOREGROUND,
            PushKeepAlivePolicy.effectiveMode(PushKeepAliveModeStore.MODE_FOREGROUND),
        )
        assertEquals(
            PushKeepAliveModeStore.MODE_OFF,
            PushKeepAlivePolicy.effectiveMode(PushKeepAliveModeStore.MODE_OFF),
        )
        assertEquals(
            PushKeepAliveModeStore.MODE_OFF,
            PushKeepAlivePolicy.effectiveMode("bogus"),
        )
    }

    @Test
    fun mediaAndFakeCallDisguisesAreRetired() {
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(PushKeepAliveModeStore.MODE_MEDIA))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(PushKeepAliveModeStore.MODE_CALL))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(PushKeepAliveModeStore.MODE_FOREGROUND))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(PushKeepAliveModeStore.MODE_FOREGROUND))
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

    @Test
    fun blankAndCaseVariantsAreDisabled() {
        assertFalse(PushKeepAlivePolicy.isEnabled(""))
        assertFalse(PushKeepAlivePolicy.isEnabled(" "))
        assertFalse(PushKeepAlivePolicy.isEnabled("FOREGROUND"))
        assertFalse(PushKeepAlivePolicy.isEnabled(" off"))
    }

    @Test
    fun unknownAndBlankNormalizeToOff() {
        assertEquals(PushKeepAliveModeStore.MODE_OFF, PushKeepAlivePolicy.effectiveMode(""))
        assertEquals(PushKeepAliveModeStore.MODE_OFF, PushKeepAlivePolicy.effectiveMode("bogus"))
        assertEquals(PushKeepAliveModeStore.MODE_OFF, PushKeepAlivePolicy.effectiveMode("FOREGROUND"))
        assertEquals(PushKeepAliveModeStore.MODE_OFF, PushKeepAlivePolicy.effectiveMode(" foreground"))
    }

    @Test
    fun legacyModesStillStartServiceWithToken() {
        assertTrue(PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.MODE_MEDIA, hasToken = true))
        assertTrue(PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.MODE_CALL, hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.MODE_MEDIA, hasToken = false))
    }

    @Test
    fun resurrectMirrorsStartServiceForForegroundAndOff() {
        assertFalse(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_OFF, hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_FOREGROUND, hasToken = false))
        assertTrue(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_FOREGROUND, hasToken = true))
    }

    @Test
    fun retiredDisguisesNeverEnabledForAnyInput() {
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(PushKeepAliveModeStore.MODE_OFF))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession("bogus"))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(""))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(PushKeepAliveModeStore.MODE_OFF))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall("bogus"))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(""))
    }

    @Test
    fun resurrectMediaModeNeedsTokenToo() {
        assertTrue(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_MEDIA, hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAliveModeStore.MODE_MEDIA, hasToken = false))
    }

    @Test
    fun effectiveModeIsIdempotent() {
        val modes = listOf(
            PushKeepAliveModeStore.MODE_OFF,
            PushKeepAliveModeStore.MODE_FOREGROUND,
            PushKeepAliveModeStore.MODE_MEDIA,
            PushKeepAliveModeStore.MODE_CALL,
            "bogus",
            "",
            "FOREGROUND",
        )
        for (mode in modes) {
            val once = PushKeepAlivePolicy.effectiveMode(mode)
            assertEquals(once, PushKeepAlivePolicy.effectiveMode(once))
        }
    }

    @Test
    fun startServiceRejectsBlankAndUnknownEvenWithToken() {
        assertFalse(PushKeepAlivePolicy.shouldStartService("", hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldStartService("bogus", hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldStartService(" foreground", hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldStartService("FOREGROUND", hasToken = true))
    }

    @Test
    fun modeConstantsAreDistinctAndAllModesCovered() {
        val modes = listOf(
            PushKeepAliveModeStore.MODE_OFF,
            PushKeepAliveModeStore.MODE_FOREGROUND,
            PushKeepAliveModeStore.MODE_MEDIA,
            PushKeepAliveModeStore.MODE_CALL,
        )
        assertEquals(4, modes.toSet().size)
        assertEquals(modes, PushKeepAliveModeStore.ALL_MODES)
        assertFalse(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_OFF))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_FOREGROUND))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_MEDIA))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAliveModeStore.MODE_CALL))
    }

    @Test
    fun supportedRuntimeModesExcludeLegacy() {
        assertEquals(
            listOf(PushKeepAliveModeStore.MODE_OFF, PushKeepAliveModeStore.MODE_FOREGROUND),
            PushKeepAliveModeStore.SUPPORTED_RUNTIME_MODES,
        )
        assertFalse(PushKeepAliveModeStore.MODE_MEDIA in PushKeepAliveModeStore.SUPPORTED_RUNTIME_MODES)
        assertFalse(PushKeepAliveModeStore.MODE_CALL in PushKeepAliveModeStore.SUPPORTED_RUNTIME_MODES)
    }

}
