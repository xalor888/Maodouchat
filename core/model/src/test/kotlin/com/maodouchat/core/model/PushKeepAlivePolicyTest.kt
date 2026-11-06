package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushKeepAlivePolicyTest {

    @Test
    fun offAndUnknownModesAreDisabled() {
        assertFalse(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_OFF))
        assertFalse(PushKeepAlivePolicy.isEnabled("bogus"))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_FOREGROUND))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_MEDIA))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_CALL))
    }

    @Test
    fun legacyMediaAndCallNormalizeToForeground() {
        assertEquals(
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.effectiveMode(PushKeepAlivePolicy.MODE_MEDIA),
        )
        assertEquals(
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.effectiveMode(PushKeepAlivePolicy.MODE_CALL),
        )
        assertEquals(
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.effectiveMode(PushKeepAlivePolicy.MODE_FOREGROUND),
        )
        assertEquals(
            PushKeepAlivePolicy.MODE_OFF,
            PushKeepAlivePolicy.effectiveMode(PushKeepAlivePolicy.MODE_OFF),
        )
        assertEquals(
            PushKeepAlivePolicy.MODE_OFF,
            PushKeepAlivePolicy.effectiveMode("bogus"),
        )
    }

    @Test
    fun mediaAndFakeCallDisguisesAreRetired() {
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(PushKeepAlivePolicy.MODE_MEDIA))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(PushKeepAlivePolicy.MODE_CALL))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(PushKeepAlivePolicy.MODE_FOREGROUND))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(PushKeepAlivePolicy.MODE_FOREGROUND))
    }

    @Test
    fun serviceRequiresEnabledModeAndToken() {
        assertFalse(
            PushKeepAlivePolicy.shouldStartService(PushKeepAlivePolicy.MODE_FOREGROUND, hasToken = false)
        )
        assertFalse(
            PushKeepAlivePolicy.shouldStartService(PushKeepAlivePolicy.MODE_OFF, hasToken = true)
        )
        assertTrue(
            PushKeepAlivePolicy.shouldStartService(PushKeepAlivePolicy.MODE_FOREGROUND, hasToken = true)
        )
        assertFalse(
            PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_CALL, hasToken = false)
        )
        assertTrue(
            PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_CALL, hasToken = true)
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
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode(""))
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode("bogus"))
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode("FOREGROUND"))
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode(" foreground"))
    }

    @Test
    fun legacyModesStillStartServiceWithToken() {
        assertTrue(PushKeepAlivePolicy.shouldStartService(PushKeepAlivePolicy.MODE_MEDIA, hasToken = true))
        assertTrue(PushKeepAlivePolicy.shouldStartService(PushKeepAlivePolicy.MODE_CALL, hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldStartService(PushKeepAlivePolicy.MODE_MEDIA, hasToken = false))
    }

    @Test
    fun resurrectMirrorsStartServiceForForegroundAndOff() {
        assertFalse(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_OFF, hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_FOREGROUND, hasToken = false))
        assertTrue(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_FOREGROUND, hasToken = true))
    }

    @Test
    fun retiredDisguisesNeverEnabledForAnyInput() {
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(PushKeepAlivePolicy.MODE_OFF))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession("bogus"))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(""))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(PushKeepAlivePolicy.MODE_OFF))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall("bogus"))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall(""))
    }

    @Test
    fun resurrectMediaModeNeedsTokenToo() {
        assertTrue(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_MEDIA, hasToken = true))
        assertFalse(PushKeepAlivePolicy.shouldResurrectDaemon(PushKeepAlivePolicy.MODE_MEDIA, hasToken = false))
    }

    @Test
    fun effectiveModeIsIdempotent() {
        val modes = listOf(
            PushKeepAlivePolicy.MODE_OFF,
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.MODE_MEDIA,
            PushKeepAlivePolicy.MODE_CALL,
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
            PushKeepAlivePolicy.MODE_OFF,
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.MODE_MEDIA,
            PushKeepAlivePolicy.MODE_CALL,
        )
        assertEquals(4, modes.toSet().size)
        assertEquals(modes, PushKeepAlivePolicy.ALL_MODES)
        assertFalse(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_OFF))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_FOREGROUND))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_MEDIA))
        assertTrue(PushKeepAlivePolicy.isEnabled(PushKeepAlivePolicy.MODE_CALL))
    }

    @Test
    fun supportedRuntimeModesExcludeLegacy() {
        assertEquals(
            listOf(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.MODE_FOREGROUND),
            PushKeepAlivePolicy.SUPPORTED_RUNTIME_MODES,
        )
        assertFalse(PushKeepAlivePolicy.MODE_MEDIA in PushKeepAlivePolicy.SUPPORTED_RUNTIME_MODES)
        assertFalse(PushKeepAlivePolicy.MODE_CALL in PushKeepAlivePolicy.SUPPORTED_RUNTIME_MODES)
    }


    @Test
    fun isEnabledRejectsPaddedKnownModes() {
        assertFalse(PushKeepAlivePolicy.isEnabled(" foreground"))
        assertFalse(PushKeepAlivePolicy.isEnabled("foreground "))
        assertFalse(PushKeepAlivePolicy.isEnabled("\tforeground"))
        assertFalse(PushKeepAlivePolicy.isEnabled(" media"))
        assertFalse(PushKeepAlivePolicy.isEnabled("call\n"))
    }

    @Test
    fun effectiveModePaddedLegacyMapsToOff() {
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode(" media"))
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode("call "))
        assertEquals(PushKeepAlivePolicy.MODE_OFF, PushKeepAlivePolicy.effectiveMode("\tforeground"))
    }

    @Test
    fun resurrectMatchesStartServiceForEveryModeAndToken() {
        val modes = listOf(
            PushKeepAlivePolicy.MODE_OFF,
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.MODE_MEDIA,
            PushKeepAlivePolicy.MODE_CALL,
            "bogus",
            "",
        )
        for (mode in modes) {
            for (hasToken in listOf(true, false)) {
                assertEquals(
                    PushKeepAlivePolicy.shouldStartService(mode, hasToken),
                    PushKeepAlivePolicy.shouldResurrectDaemon(mode, hasToken),
                )
            }
        }
    }

    @Test
    fun retiredDisguisesStayFalseForCaseAndPaddedVariants() {
        assertFalse(PushKeepAlivePolicy.wantsMediaSession("MEDIA"))
        assertFalse(PushKeepAlivePolicy.wantsMediaSession(" media"))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall("CALL"))
        assertFalse(PushKeepAlivePolicy.wantsFakeCall("call "))
    }

    @Test
    fun effectiveModeOutputIsAlwaysSupportedRuntimeMode() {
        val modes = listOf(
            PushKeepAlivePolicy.MODE_OFF,
            PushKeepAlivePolicy.MODE_FOREGROUND,
            PushKeepAlivePolicy.MODE_MEDIA,
            PushKeepAlivePolicy.MODE_CALL,
            "bogus",
            "",
        )
        for (mode in modes) {
            assertTrue(
                PushKeepAlivePolicy.effectiveMode(mode) in PushKeepAlivePolicy.SUPPORTED_RUNTIME_MODES
            )
        }
    }
}
