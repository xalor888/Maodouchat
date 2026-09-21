package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G181：`toHexString` 的测试（G181 刚从 7 处私有副本收敛而来）。
 *
 * 重点是「两位 + 补零」这个硬要求，以及全 256 个字节值都不退化。
 */
class HexBytesTest {

    @Test
    fun `empty array yields an empty string`() {
        assertEquals("", ByteArray(0).toHexString())
    }

    @Test
    fun `each byte is exactly two lowercase hex digits`() {
        assertEquals("00", byteArrayOf(0x00).toHexString())
        assertEquals("0a", byteArrayOf(0x0A).toHexString())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHexString())
        assertEquals("10", byteArrayOf(0x10).toHexString())
    }

    @Test
    fun `leading zeros are kept so the length is always twice the size`() {
        // 不补零的话 0x0A 会变成 "a"，长度漂移
        val bytes = byteArrayOf(0x00, 0x01, 0x0A, 0xFF.toByte())
        val hex = bytes.toHexString()
        assertEquals("00010aff", hex)
        assertEquals(bytes.size * 2, hex.length)
    }

    @Test
    fun `every byte value round trips through two digits`() {
        for (v in 0..255) {
            val hex = byteArrayOf(v.toByte()).toHexString()
            assertEquals("字节 $v 长度不对", 2, hex.length)
            assertEquals("字节 $v 不是小写十六进制", hex, hex.lowercase())
            assertEquals("字节 $v 解析不回去", v, hex.toInt(16))
        }
    }

    @Test
    fun `a long array stays the same length and is deterministic`() {
        val bytes = ByteArray(1000) { (it % 256).toByte() }
        val first = bytes.toHexString()
        assertEquals(2000, first.length)
        assertEquals("同输入必须同输出", first, bytes.toHexString())
    }

    @Test
    fun `output only ever contains hex digits`() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = bytes.toHexString()
        assertTrue(hex.all { it in "0123456789abcdef" })
    }

    private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)
}
