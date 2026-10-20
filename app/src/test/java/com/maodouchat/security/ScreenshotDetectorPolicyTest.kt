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

    @Test
    fun `screenshot name-only variants still match when path is plain`() {
        assertTrue(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "Screenshot_20261021_120000.png"))
        assertTrue(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "screen_20261021.jpg"))
        assertFalse(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "IMG_0001.jpg"))
    }

    @Test
    fun `screenshot path spelling variants match`() {
        assertTrue(ScreenshotDetector.isScreenshotPath("Pictures/Screen_shot/", "pic.png"))
        assertTrue(ScreenshotDetector.isScreenshotPath("Pictures/Screen-shot/", "pic.png"))
        assertFalse(ScreenshotDetector.isScreenshotPath("Pictures/Wallpapers/", "pic.png"))
    }

    @Test
    fun `screenrecord path and name variants match`() {
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/Screen recordings/", "clip.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/", "screenrecord_1.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/", "screen_record_final.mp4"))
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "clip.mp4"))
    }

    @Test
    fun `screenrecord name needs screen or scr context`() {
        // Bare "record"/"recording" names are not capture evidence; the screen context avoids flagging plain clips.
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "record_1.mp4"))
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "recording.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/", "screenie_record.mp4"))
    }

    @Test
    fun `scrubber keeps images within 8 seconds and video within 12 seconds`() {
        assertTrue(
            SecureCaptureScrubber.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 8)
        )
        assertFalse(
            SecureCaptureScrubber.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 9)
        )
        assertTrue(
            SecureCaptureScrubber.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 12)
        )
        assertFalse(
            SecureCaptureScrubber.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 13)
        )
        assertFalse(
            SecureCaptureScrubber.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", -1)
        )
    }
}
