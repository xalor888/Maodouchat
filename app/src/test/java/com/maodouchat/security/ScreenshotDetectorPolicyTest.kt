package com.maodouchat.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotDetectorPolicyTest {
    @Test
    fun `secret chat starts detector even when flag is off`() {
        assertTrue(ScreenshotDetector.shouldStart(secretActive = true, screenshotDetectFlag = false))
        assertTrue(ScreenshotDetector.shouldStart(secretActive = true, screenshotDetectFlag = true))
    }

    @Test
    fun `non-secret disappearing messages follow the flag`() {
        assertFalse(ScreenshotDetector.shouldStart(secretActive = false, screenshotDetectFlag = false))
        assertTrue(ScreenshotDetector.shouldStart(secretActive = false, screenshotDetectFlag = true))
    }

    @Test
    fun `screenshot path helpers still match gallery names`() {
        assertTrue(ScreenshotDetector.isScreenshotPath("Pictures/Screenshots/", "Screenshot_1.png"))
        assertTrue(ScreenshotDetector.isScreenshotPath("DCIM/截图/", "截屏_1.jpg"))
        assertFalse(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "IMG_0001.jpg"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/Screen recordings/", "screenrecord.mp4"))
    }
}
