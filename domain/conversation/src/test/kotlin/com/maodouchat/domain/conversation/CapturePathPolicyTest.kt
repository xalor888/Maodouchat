package com.maodouchat.domain.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePathPolicyTest {
    @Test
    fun `secret chat starts detector even when flag is off`() {
        assertTrue(CapturePathPolicy.shouldStart(secretActive = true, screenshotDetectFlag = false))
        assertTrue(CapturePathPolicy.shouldStart(secretActive = true, screenshotDetectFlag = true))
    }

    @Test
    fun `non-secret disappearing messages follow the flag`() {
        assertFalse(CapturePathPolicy.shouldStart(secretActive = false, screenshotDetectFlag = false))
        assertTrue(CapturePathPolicy.shouldStart(secretActive = false, screenshotDetectFlag = true))
    }

    @Test
    fun `screenshot path helpers still match gallery names`() {
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/Screenshots/", "Screenshot_1.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/截图/", "截屏_1.jpg"))
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "IMG_0001.jpg"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screen recordings/", "screenrecord.mp4"))
    }

    @Test
    fun `screenshot name-only variants still match when path is plain`() {
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "Screenshot_20261021_120000.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "screen_20261021.jpg"))
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "IMG_0001.jpg"))
    }

    @Test
    fun `screenshot path spelling variants match`() {
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/Screen_shot/", "pic.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/Screen-shot/", "pic.png"))
        assertFalse(CapturePathPolicy.isScreenshotPath("Pictures/Wallpapers/", "pic.png"))
    }

    @Test
    fun `screenrecord path and name variants match`() {
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screen recordings/", "clip.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "screenrecord_1.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "screen_record_final.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "clip.mp4"))
    }

    @Test
    fun `screenrecord name needs screen or scr context`() {
        // Bare "record"/"recording" names are not capture evidence; the screen context avoids flagging plain clips.
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "record_1.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "recording.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "screenie_record.mp4"))
    }

    @Test
    fun `scrubber keeps images within 8 seconds and video within 12 seconds`() {
        assertTrue(
            CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 8)
        )
        assertFalse(
            CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 9)
        )
        assertTrue(
            CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 12)
        )
        assertFalse(
            CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 13)
        )
        assertFalse(
            CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", -1)
        )
    }

    @Test
    fun `shouldStart truth table is secret or flag`() {
        assertFalse(CapturePathPolicy.shouldStart(secretActive = false, screenshotDetectFlag = false))
        assertTrue(CapturePathPolicy.shouldStart(secretActive = true, screenshotDetectFlag = false))
        assertTrue(CapturePathPolicy.shouldStart(secretActive = false, screenshotDetectFlag = true))
        assertTrue(CapturePathPolicy.shouldStart(secretActive = true, screenshotDetectFlag = true))
    }

    @Test
    fun `screenshot matching is case insensitive for path and name`() {
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/SCREENSHOTS/", "pic.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/ScreenShots/", ""))
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "myScreenshot_1.png"))
        assertFalse(CapturePathPolicy.isScreenshotPath("Pictures/Screen/", "pic.png"))
    }

    @Test
    fun `screenshot matching covers chinese path and name`() {
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "我的截图.jpg"))
        assertTrue(CapturePathPolicy.isScreenshotPath("相册/截屏/", "pic.png"))
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "风景照.jpg"))
    }

    @Test
    fun `screen record matching covers chinese and hyphen variants`() {
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/录屏/", "clip.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("DCIM/Camera/", "我的录屏.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screen-recording/", "clip.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "myrecording.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "clip_rec.mp4"))
    }

    @Test
    fun `empty and bare screen inputs do not match`() {
        assertFalse(CapturePathPolicy.isScreenshotPath("", ""))
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", ""))
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "screen.png"))
        assertFalse(CapturePathPolicy.isScreenshotPath("Pictures/Screen/", "screen.png"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("", ""))
    }

    @Test
    fun `record path and name spelling variants match`() {
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screenrecording/", "clip.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screen_record/", "clip.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "scr_record.mp4"))
        // rec 只是 record 的缩写，单独出现不算证据（与 clip_rec 的否定断言一致）。
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "rec_screen.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "screenclip.mp4"))
    }

    @Test
    fun `scrubber video window is wider than image window`() {
        assertFalse(CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", -1))
        assertFalse(CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 10))
        assertTrue(CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 10))
    }

    @Test
    fun `mid-string screen shot name does not match but path does`() {
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "my_screen_shot.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/Screen_shot/", "my_screen_shot.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "screen_shot_1.png"))
    }

    @Test
    fun `scrubber never deletes across capture types`() {
        assertFalse(CapturePathPolicy.shouldDeleteImage("Movies/Screen recordings/", "screenrecord.mp4", 5))
        assertFalse(CapturePathPolicy.shouldDeleteVideo("Pictures/Screenshots/", "Screenshot_1.png", 5))
    }

    @Test
    fun `scrubber age zero passes but a non capture path never does`() {
        assertTrue(CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 0))
        assertTrue(CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 0))
        assertFalse(CapturePathPolicy.shouldDeleteImage("DCIM/Camera/", "IMG_0001.jpg", 0))
        assertFalse(CapturePathPolicy.shouldDeleteVideo("Movies/", "clip.mp4", 0))
    }

}
