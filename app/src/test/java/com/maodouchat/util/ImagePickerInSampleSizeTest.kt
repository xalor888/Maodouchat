package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G207b：`ImagePicker.calculateInSampleSize` —— 图片解码的采样率计算。
 *
 * 为什么值得测：它是 `BitmapFactory` 风格的降采样决策，直接决定**解码时占多少内存**。
 * 上游有 `MAX_IMAGE_PIXELS = 4_000_000` 这道**解压炸弹**防线——
 * 一张号称 40000×40000（16 亿像素）的图，若不先按 sampleSize 降采样就直接解码，
 * 光位图就是数 GB，OOM。
 *
 * 这里断言三件事：
 * 1. **返回值必须真的满足两个约束**（最长边 ≤ maxWidth **且** 像素数 ≤ maxPixels）——
 *    否则这道防线就是摆设；
 * 2. **必须是 2 的幂**（`BitmapFactory.Options.inSampleSize` 的硬要求，非幂会被忽略）；
 * 3. **必须终止**（循环里有 `sampleSize > Int.MAX_VALUE / 2` 的兜底，
 *    退化输入不能让它挂死）。
 */
class ImagePickerInSampleSizeTest {

    private fun calc(w: Int, h: Int, maxWidth: Int = 800, maxPixels: Int = 4_000_000) =
        ImagePicker.calculateInSampleSize(w, h, maxWidth, maxPixels)

    /** 返回值必须同时满足两个约束（这是防线的本体）。 */
    private fun satisfies(sample: Int, w: Int, h: Int, maxWidth: Int, maxPixels: Int): Boolean {
        if (sample < 1) return false
        val sw = maxOf(w / sample, 1)
        val sh = maxOf(h / sample, 1)
        return maxOf(sw, sh) <= maxOf(maxWidth, 1) &&
            sw.toLong() * sh.toLong() <= maxOf(maxPixels, 1).toLong()
    }

    private fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    @Test
    fun smallImagesAreNotDownsampled() {
        // 已经够小 → sampleSize 1（原图解码，不白丢画质）
        assertEquals(1, calc(640, 480))
        assertEquals(1, calc(800, 600))       // 正好等于 maxWidth 的边界
        assertEquals(1, calc(1, 1))
    }

    @Test
    fun wideImagesAreDownsampledUntilWidthFits() {
        val s = calc(6400, 4800, maxWidth = 800)
        assertTrue(satisfies(s, 6400, 4800, 800, 4_000_000), "采样后必须满足约束，实际 $s")
        assertTrue(isPowerOfTwo(s), "sampleSize 必须是 2 的幂，实际 $s")
        assertTrue(s >= 4, "6400/4=1600 > 800，至少要降到 4，实际 $s")
    }

    @Test
    fun pixelBudgetForcesExtraDownsampling() {
        // 边不长但像素极多（细长条）：4_000_000 像素上限会把 sampleSize 推上去
        val s = calc(20000, 200, maxWidth = 800, maxPixels = 4_000_000)
        assertTrue(satisfies(s, 20000, 200, 800, 4_000_000), "必须同时满足两个约束，实际 $s")
        assertTrue(isPowerOfTwo(s), "sampleSize 必须是 2 的幂，实际 $s")
    }

    @Test
    fun hugeImagesTerminateInsteadOfHanging() {
        // 解压炸弹：40000x40000 = 16 亿像素。必须终止且满足约束。
        val s = calc(40_000, 40_000, maxWidth = 800, maxPixels = 4_000_000)
        assertTrue(satisfies(s, 40_000, 40_000, 800, 4_000_000), "巨图也必须满足约束，实际 $s")
        assertTrue(isPowerOfTwo(s), "巨图的 sampleSize 仍须是 2 的幂，实际 $s")
    }

    @Test
    fun degenerateInputsTerminateWithAUsableSampleSize() {
        // 这些值来自不可信的 EXIF/文件头，不能让循环挂死
        listOf(
            Triple(0, 0, 800),
            Triple(-1, -1, 800),
            Triple(100, 100, 0),
            Triple(100, 100, -5),
            Triple(0, 0, 0),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
        ).forEach { (w, h, mw) ->
            val s = calc(w, h, mw, 4_000_000)
            assertTrue(s >= 1, "($w,$h,$mw) 的 sampleSize 必须 >= 1，实际 $s")
            assertTrue(isPowerOfTwo(s) || s == Int.MAX_VALUE, "($w,$h,$mw) 应为 2 的幂或 Int.MAX_VALUE，实际 $s")
        }
    }

    @Test
    fun resultAlwaysSatisfiesBothConstraintsAcrossARange() {
        // 横扫一批尺寸：任何输入下返回值都必须满足两个约束（防线的完整表述）
        val sizes = listOf(1 to 1, 100 to 1, 800 to 600, 1920 to 1080, 4000 to 3000, 12000 to 9000)
        val widths = listOf(1, 100, 800, 4000)
        val pixels = listOf(1, 10_000, 4_000_000)
        for ((w, h) in sizes) for (mw in widths) for (mp in pixels) {
            val s = calc(w, h, mw, mp)
            assertTrue(
                satisfies(s, w, h, mw, mp),
                "w=$w h=$h maxWidth=$mw maxPixels=$mp -> sampleSize=$s 未满足约束",
            )
            assertTrue(isPowerOfTwo(s) || s == Int.MAX_VALUE, "w=$w h=$h mw=$mw mp=$mp -> $s 不是 2 的幂")
        }
    }
}
