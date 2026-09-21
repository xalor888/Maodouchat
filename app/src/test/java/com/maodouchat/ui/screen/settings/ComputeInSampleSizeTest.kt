package com.maodouchat.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G153：`WatermarkForensicScreen.computeInSampleSize` 的测试。
 *
 * 它是 Android 官方「二次采样」算法：只要有一边还大于请求尺寸，就继续减半，
 * 结果是 2 的幂（或 1）。两个易错点：
 * 1. **`>=` 而非 `>`**——减半后「恰好等于」请求尺寸时仍会再减一次；
 * 2. **两边都要满足**——`&&` 条件让大图在小请求方向上先停。
 */
class ComputeInSampleSizeTest {

    @Test
    fun `non positive source size samples at one`() {
        assertEquals(1, computeInSampleSize(0, 100, 50, 50))
        assertEquals(1, computeInSampleSize(100, 0, 50, 50))
        assertEquals(1, computeInSampleSize(-10, 100, 50, 50))
        assertEquals(1, computeInSampleSize(100, -10, 50, 50))
    }

    @Test
    fun `already smaller than the request samples at one`() {
        assertEquals(1, computeInSampleSize(40, 40, 50, 50))
        assertEquals(1, computeInSampleSize(50, 50, 50, 50))
    }

    @Test
    fun `power of two halving`() {
        // 4000x3000 请求 1000x1000：halfH=1500, halfW=2000
        //   1500/1>=1000 && 2000/1>=1000 -> 加倍成 2
        //   1500/2=750 >=1000 不成立 -> 停，结果 2
        assertEquals(2, computeInSampleSize(4000, 3000, 1000, 1000))
        // 8000x6000 请求 1000x1000：能加倍两次 -> 4
        assertEquals(4, computeInSampleSize(8000, 6000, 1000, 1000))
        // 16000x12000 请求 1000x1000：能加倍三次 -> 8
        assertEquals(8, computeInSampleSize(16000, 12000, 1000, 1000))
    }

    @Test
    fun `halving stops when the half equals the request`() {
        // 200x200 请求 100x100：half=100，100/1=100 >= 100 成立 -> 再减半成 2
        // （`>=` 让「恰好相等」也继续减）
        assertEquals(2, computeInSampleSize(200, 200, 100, 100))
        // 199x199 请求 100x100：half=99，99/1=99 < 100 -> 不减半
        assertEquals(1, computeInSampleSize(199, 199, 100, 100))
    }

    @Test
    fun `both dimensions must satisfy the halving condition`() {
        // 很宽但很矮：高度先不满足，停在 1
        assertEquals(1, computeInSampleSize(4000, 100, 1000, 100))
        // 很高但很窄：宽度先不满足，停在 1
        assertEquals(1, computeInSampleSize(100, 4000, 100, 1000))
    }

    // 注意：reqWidth/reqHeight 传 0 时 `half/inSampleSize >= 0` 永真，
    // inSampleSize 会一路倍增直到 Int 溢出成 0 再除零崩溃——这是既有隐患，
    // 调用方（Bitmap 采样）从不会传 0，所以不为此加用例。
}
