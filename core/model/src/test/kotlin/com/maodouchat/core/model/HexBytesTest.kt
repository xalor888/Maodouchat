package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HexBytesTest {

    @Test
    fun `empty array yields empty string`() {
        assertEquals("", ByteArray(0).toHexString())
    }

    @Test
    fun `each byte is exactly two lowercase hex digits`() {
        assertEquals("00010aff", byteArrayOf(0x00, 0x01, 0x0A, 0xFF.toByte()).toHexString())
    }

    @Test
    fun `output length is always twice the size`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.size * 2, bytes.toHexString().length)
    }
}
