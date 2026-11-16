package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotAlertKindTest {

    @Test
    fun `all four combinations map to their kinds`() {
        assertEquals(ScreenshotAlertKind.BOTH, screenshotAlertKind(secretActive = true, disappearingActive = true))
        assertEquals(ScreenshotAlertKind.SECRET_ONLY, screenshotAlertKind(secretActive = true, disappearingActive = false))
        assertEquals(ScreenshotAlertKind.DISAPPEARING_ONLY, screenshotAlertKind(secretActive = false, disappearingActive = true))
        assertEquals(ScreenshotAlertKind.DISAPPEARING_ONLY, screenshotAlertKind(secretActive = false, disappearingActive = false))
    }
}
