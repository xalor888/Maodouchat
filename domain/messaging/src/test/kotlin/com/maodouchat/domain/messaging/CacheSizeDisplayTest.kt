package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class CacheSizeDisplayTest {

    @Test
    fun zeroBytes() {
        assertEquals("0 B", CacheSizeDisplay.formatSize(0))
    }

    @Test
    fun belowKilobyteUsesBytes() {
        assertEquals("1023 B", CacheSizeDisplay.formatSize(1023))
    }

    @Test
    fun kilobyteBoundaryUsesIntegerDivision() {
        assertEquals("1 KB", CacheSizeDisplay.formatSize(1024))
        assertEquals("12 KB", CacheSizeDisplay.formatSize(12 * 1024 + 512))
    }

    @Test
    fun megabyteBoundary() {
        assertEquals("1 MB", CacheSizeDisplay.formatSize(1024L * 1024L))
        assertEquals("3 MB", CacheSizeDisplay.formatSize(3 * 1024L * 1024L))
    }

    @Test
    fun gigabyteBoundary() {
        assertEquals("1 GB", CacheSizeDisplay.formatSize(1024L * 1024L * 1024L))
        assertEquals("2 GB", CacheSizeDisplay.formatSize(2 * 1024L * 1024L * 1024L))
    }

    @Test
    fun negativeStaysInBytesBucket() {
        assertEquals("-5 B", CacheSizeDisplay.formatSize(-5))
    }
}
