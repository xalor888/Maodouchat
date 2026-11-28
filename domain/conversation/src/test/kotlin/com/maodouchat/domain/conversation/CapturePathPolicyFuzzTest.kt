package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [CapturePathPolicy] (checklist line 931, 44th block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements both path predicates from scratch: it lowercases the
 * inputs with the same `lowercase()` call and then spells out the disjunction
 * term by term (path terms vs name terms, startsWith vs contains, and the
 * `record && (screen || scr)` grouping of isScreenRecordPath, where && binds
 * tighter than ||). A Python mini-model differentially checks this oracle
 * against real JDK 17 + kotlin-stdlib 2.4.0 semantics (Fuzz44Check) on 5000
 * random (path, name) pairs, a 119-char lowercase sweep and 1500 blank strings
 * (0 diffs), before this file was written.
 *
 * Two asymmetries the oracle must reproduce: the name-only `screen_` prefix
 * rule (a "screen_..." name matches even on a plain path, while "my_screen_shot"
 * does not), and the fresh windows (image 0..8s, video 0..12s, negative ages
 * never delete). `lowercase()` is locale-sensitive in principle, but oracle and
 * production run in the same JVM, so they always agree; the differential only
 * validates the boolean structure. The oracle hardcodes the 8s/12s windows as
 * literals and the pinned test asserts the production constants equal them.
 */
class CapturePathPolicyFuzzTest {

    private fun oracleIsScreenshotPath(path: String, name: String): Boolean {
        val p = path.lowercase()
        val n = name.lowercase()
        val pathTerms = listOf("screenshot", "screen_shot", "screen-shot", "截屏", "截图")
        if (pathTerms.any { it in p }) return true
        if (n.startsWith("screenshot") || n.startsWith("screen_")) return true
        return "screenshot" in n || "截屏" in n || "截图" in n
    }

    private fun oracleIsScreenRecordPath(path: String, name: String): Boolean {
        val p = path.lowercase()
        val n = name.lowercase()
        val pathTerms = listOf(
            "screenrecord", "screen_record", "screen-record",
            "screen recordings", "screenrecording", "录屏"
        )
        if (pathTerms.any { it in p }) return true
        if ("screenrecord" in n || "screen_record" in n || "录屏" in n) return true
        return "record" in n && ("screen" in n || "scr" in n)
    }

    private fun oracleShouldStart(secretActive: Boolean, screenshotDetectFlag: Boolean): Boolean =
        secretActive || screenshotDetectFlag

    private fun oracleShouldDeleteImage(path: String, name: String, ageSec: Long): Boolean =
        ageSec in 0L..8L && oracleIsScreenshotPath(path, name)

    private fun oracleShouldDeleteVideo(path: String, name: String, ageSec: Long): Boolean =
        ageSec in 0L..12L && oracleIsScreenRecordPath(path, name)

    private val pathWords = listOf(
        "Pictures", "DCIM", "Movies", "Screenshots", "Camera", "Screen_shot",
        "Screen-shot", "截屏", "截图", "录屏", "Screen recordings", "Screenrecording",
        "Screen_record", "Wallpapers", "Download", "My Files", "screen", "SCREEN",
        "Screen", "record", "scr", "clip", "IMG", "My", "2026", "Vacation"
    )

    private val nameWords = listOf(
        "Screenshot_20261021_120000.png", "Screenshot_1.png", "screen_20261021.jpg",
        "IMG_0001.jpg", "screenrecord.mp4", "screen_record_final.mp4",
        "scr_record.mp4", "rec_screen.mp4", "record_1.mp4", "recording.mp4",
        "screenie_record.mp4", "my_screen_shot.png", "clip.mp4",
        "我的截图.jpg", "我的录屏.mp4", "截屏_1.jpg", "SCREENSHOT.PNG",
        "screenclip.mp4", "clip_rec.mp4", "myrecording.mp4", "Screen-recording.mp4",
        "Screen_shot_2.png", "pic.png", "video.mp4", "ScreenRecord_9.mp4",
        "SCREEN_RECORD_1.mp4"
    )

    // One string per code point, so random strings stay well-formed (no lone surrogates).
    private val edgeChars = listOf(
        "\u0130", "\u0131", "\u00DF", "\u1E9E", "\uFB00", "\u017F",
        "\u212A", "\u00C5", "\u00E5", "\u03A3", "\u03C3", "\u03C2",
        "\uD801\uDC00", "\uD801\uDC28", "\uD801\uDCD0", "\uD801\uDCF8",
        "\uD83D\uDE00", "\u00B5", "\u039C", "\u03BC", "\u10A0", "\u2D00"
    )

    private fun randomPath(rnd: Random): String {
        val parts = List(rnd.nextInt(0, 4)) { pathWords.random(rnd) }.toMutableList()
        if (parts.isNotEmpty() && rnd.nextInt(10) < 3) {
            val i = rnd.nextInt(parts.size)
            parts[i] = when (rnd.nextInt(10)) {
                in 0..3 -> parts[i].uppercase()
                in 4..7 -> parts[i].lowercase()
                else -> parts[i]
            }
        }
        val sep = listOf("/", "/", "/", " ", "").random(rnd)
        var s = parts.joinToString(sep)
        if (parts.isNotEmpty() && rnd.nextInt(10) < 4) s += "/"
        if (rnd.nextInt(100) < 15) s += edgeChars.random(rnd)
        return s
    }

    private fun randomName(rnd: Random): String {
        val parts = List(rnd.nextInt(0, 3)) { nameWords.random(rnd) }.toMutableList()
        if (parts.isNotEmpty() && rnd.nextInt(10) < 3) {
            val i = rnd.nextInt(parts.size)
            parts[i] = when (rnd.nextInt(10)) {
                in 0..3 -> parts[i].uppercase()
                in 4..7 -> parts[i].lowercase()
                else -> parts[i]
            }
        }
        var s = parts.joinToString(listOf("_", "_", " ", "").random(rnd))
        if (rnd.nextInt(100) < 15) s = edgeChars.random(rnd) + s
        return s
    }

    private fun randomAge(rnd: Random): Long = when (rnd.nextInt(10)) {
        in 0..2 -> listOf(-10L, -1L, 0L, 1L, 7L, 8L, 9L, 11L, 12L, 13L, 100L).random(rnd)
        in 3..5 -> rnd.nextLong(-20L, 21L)
        in 6..7 -> rnd.nextLong()
        else -> rnd.nextLong(-1000L, 1001L)
    }

    @Test
    fun isScreenshotPathRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val path = randomPath(rnd)
            val name = randomName(rnd)
            assertEquals(
                oracleIsScreenshotPath(path, name),
                CapturePathPolicy.isScreenshotPath(path, name),
                "path=<$path> name=<$name>"
            )
        }
    }

    @Test
    fun isScreenRecordPathRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val path = randomPath(rnd)
            val name = randomName(rnd)
            assertEquals(
                oracleIsScreenRecordPath(path, name),
                CapturePathPolicy.isScreenRecordPath(path, name),
                "path=<$path> name=<$name>"
            )
        }
    }

    @Test
    fun shouldDeleteRandomOracle() {
        val rnd = Random(42)
        repeat(200) {
            val path = randomPath(rnd)
            val name = randomName(rnd)
            val age = randomAge(rnd)
            assertEquals(
                oracleShouldDeleteImage(path, name, age),
                CapturePathPolicy.shouldDeleteImage(path, name, age),
                "image path=<$path> name=<$name> age=$age"
            )
            assertEquals(
                oracleShouldDeleteVideo(path, name, age),
                CapturePathPolicy.shouldDeleteVideo(path, name, age),
                "video path=<$path> name=<$name> age=$age"
            )
        }
    }

    @Test
    fun shouldStartRandomOracle() {
        val rnd = Random(42)
        for ((secret, flag) in listOf(false to false, false to true, true to false, true to true)) {
            assertEquals(
                oracleShouldStart(secret, flag),
                CapturePathPolicy.shouldStart(secret, flag)
            )
        }
        repeat(100) {
            val secret = rnd.nextBoolean()
            val flag = rnd.nextBoolean()
            assertEquals(
                oracleShouldStart(secret, flag),
                CapturePathPolicy.shouldStart(secret, flag)
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Windows are literals in the oracle; the constants must stay 8s / 12s.
        assertEquals(8L, CapturePathPolicy.IMAGE_MAX_AGE_SEC)
        assertEquals(12L, CapturePathPolicy.VIDEO_MAX_AGE_SEC)

        // "record" alone is not evidence; it needs a screen/scr context.
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "record_1.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "recording.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "screenie_record.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "scr_record.mp4"))
        // "rec_screen" has no "record" substring (rec + _screen), so it stays false.
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "rec_screen.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "screenclip.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "clip_rec.mp4"))
        assertFalse(CapturePathPolicy.isScreenRecordPath("Movies/", "myrecording.mp4"))

        // Name-only "screen_" prefix matches on a plain path; "my_screen_shot" does not.
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "my_screen_shot.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/Screen_shot/", "my_screen_shot.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "screen_shot_1.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/SCREENSHOTS/", "pic.png"))
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "myScreenshot_1.png"))
        assertFalse(CapturePathPolicy.isScreenshotPath("Pictures/Screen/", "pic.png"))

        // One name can hit both policies: "Screen_Record_..." starts with "screen_"
        // and contains "screen_record".
        assertTrue(CapturePathPolicy.isScreenshotPath("Movies/", "Screen_Record_1.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/", "Screen_Record_1.mp4"))

        // "Screen recordings" with a space is a path term; record path never matches screenshot.
        assertFalse(CapturePathPolicy.isScreenshotPath("Pictures/Screen recordings/", "screenrecord.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Pictures/Screen recordings/", "screenrecord.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screen-recording/", "clip.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/Screenrecording/", "clip.mp4"))

        // Turkish dotted capital I lowercases to 'i' + combining dot, so it breaks
        // "screenshot"; plain upper-case SCREENSHOT still matches.
        assertTrue(CapturePathPolicy.isScreenshotPath("Pictures/SCREENSHOT/", ""))
        assertFalse(CapturePathPolicy.isScreenshotPath("Pictures/SCREENSHO\u0130/", ""))

        // Chinese terms on path and on name, both directions.
        assertTrue(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "我的截图.jpg"))
        assertTrue(CapturePathPolicy.isScreenshotPath("相册/截屏/", "pic.png"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("Movies/录屏/", "clip.mp4"))
        assertTrue(CapturePathPolicy.isScreenRecordPath("DCIM/Camera/", "我的录屏.mp4"))

        // Empty inputs match nothing.
        assertFalse(CapturePathPolicy.isScreenshotPath("", ""))
        assertFalse(CapturePathPolicy.isScreenRecordPath("", ""))
        assertFalse(CapturePathPolicy.isScreenshotPath("DCIM/Camera/", "screen.png"))

        // Fresh windows: image 0..8s, video 0..12s; cross-type never deletes.
        assertTrue(CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 0))
        assertTrue(CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 8))
        assertFalse(CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", 9))
        assertFalse(CapturePathPolicy.shouldDeleteImage("Pictures/Screenshots/", "Screenshot_1.png", -1))
        assertTrue(CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 12))
        assertFalse(CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", 13))
        assertFalse(CapturePathPolicy.shouldDeleteVideo("Movies/Screen recordings/", "screenrecord.mp4", -1))
        assertFalse(CapturePathPolicy.shouldDeleteImage("Movies/Screen recordings/", "screenrecord.mp4", 5))
        assertFalse(CapturePathPolicy.shouldDeleteVideo("Pictures/Screenshots/", "Screenshot_1.png", 5))
        assertFalse(CapturePathPolicy.shouldDeleteImage("DCIM/Camera/", "IMG_0001.jpg", 0))
    }
}
