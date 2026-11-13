package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCenterDisplayTest {

    @Test
    fun bytesBelowKilobyteUseBytes() {
        assertEquals("0 B", MediaCenterDisplay.formatBytes(0))
        assertEquals("512 B", MediaCenterDisplay.formatBytes(512))
        assertEquals("1023 B", MediaCenterDisplay.formatBytes(1023))
    }

    @Test
    fun kilobytesUseOneDecimal() {
        assertEquals("1.0 KB", MediaCenterDisplay.formatBytes(1024))
        assertEquals("1.5 KB", MediaCenterDisplay.formatBytes(1536))
    }

    @Test
    fun megabytesUseOneDecimal() {
        assertEquals("1.0 MB", MediaCenterDisplay.formatBytes(1024L * 1024L))
        assertEquals("2.5 MB", MediaCenterDisplay.formatBytes((2.5 * 1024 * 1024).toLong()))
    }
}
