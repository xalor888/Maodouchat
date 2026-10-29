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

    @Test
    fun `shouldStart truth table is secret or flag`() {
        assertFalse(ScreenshotDetector.shouldStart(secretActive = false, screenshotDetectFlag = false))
        assertTrue(ScreenshotDetector.shouldStart(secretActive = true, screenshotDetectFlag = false))
        assertTrue(ScreenshotDetector.shouldStart(secretActive = false, screenshotDetectFlag = true))
        assertTrue(ScreenshotDetector.shouldStart(secretActive = true, screenshotDetectFlag = true))
    }

    @Test
    fun `screenshot matching is case insensitive for path and name`() {
        assertTrue(ScreenshotDetector.isScreenshotPath("Pictures/SCREENSHOTS/", "pic.png"))
        assertTrue(ScreenshotDetector.isScreenshotPath("Pictures/ScreenShots/", ""))
        assertTrue(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "myScreenshot_1.png"))
        assertFalse(ScreenshotDetector.isScreenshotPath("Pictures/Screen/", "pic.png"))
    }

    @Test
    fun `screenshot matching covers chinese path and name`() {
        assertTrue(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "我的截图.jpg"))
        assertTrue(ScreenshotDetector.isScreenshotPath("相册/截屏/", "pic.png"))
        assertFalse(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "风景照.jpg"))
    }

    @Test
    fun `screen record matching covers chinese and hyphen variants`() {
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/录屏/", "clip.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("DCIM/Camera/", "我的录屏.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/Screen-recording/", "clip.mp4"))
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "myrecording.mp4"))
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "clip_rec.mp4"))
    }

    @Test
    fun `empty and bare screen inputs do not match`() {
        assertFalse(ScreenshotDetector.isScreenshotPath("", ""))
        assertFalse(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", ""))
        assertFalse(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "screen.png"))
        assertFalse(ScreenshotDetector.isScreenshotPath("Pictures/Screen/", "screen.png"))
        assertFalse(ScreenshotDetector.isScreenRecordPath("", ""))
    }

    @Test
    fun `record path and name spelling variants match`() {
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/Screenrecording/", "clip.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/Screen_record/", "clip.mp4"))
        assertTrue(ScreenshotDetector.isScreenRecordPath("Movies/", "scr_record.mp4"))
        // rec 只是 record 的缩写，单独出现不算证据（与 clip_rec 的否定断言一致）。
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "rec_screen.mp4"))
        assertFalse(ScreenshotDetector.isScreenRecordPath("Movies/", "screenclip.mp4"))
    }

    @Test
    fun `scrubber video window is wider than image window`() {
        assertFalse(SecureCaptureScrubber.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", -1))
        assertFalse(SecureCaptureScrubber.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 10))
        assertTrue(SecureCaptureScrubber.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 10))
    }

    @Test
    fun `mid-string screen shot name does not match but path does`() {
        assertFalse(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "my_screen_shot.png"))
        assertTrue(ScreenshotDetector.isScreenshotPath("Pictures/Screen_shot/", "my_screen_shot.png"))
        assertTrue(ScreenshotDetector.isScreenshotPath("DCIM/Camera/", "screen_shot_1.png"))
    }

    @Test
    fun `scrubber never deletes across capture types`() {
        assertFalse(SecureCaptureScrubber.shouldDeleteImage("Movies/Screen recordings/", "screenrecord.mp4", 5))
        assertFalse(SecureCaptureScrubber.shouldDeleteVideo("Pictures/Screenshots/", "Screenshot_1.png", 5))
    }

    @Test
    fun `scrubber age zero passes but a non capture path never does`() {
        assertTrue(SecureCaptureScrubber.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 0))
        assertTrue(SecureCaptureScrubber.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 0))
        assertFalse(SecureCaptureScrubber.shouldDeleteImage("DCIM/Camera/", "IMG_0001.jpg", 0))
        assertFalse(SecureCaptureScrubber.shouldDeleteVideo("Movies/", "clip.mp4", 0))
    }

}
