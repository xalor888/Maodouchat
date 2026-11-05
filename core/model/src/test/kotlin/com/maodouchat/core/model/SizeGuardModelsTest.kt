package com.maodouchat.core.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SizeGuardModelsTest {

    @Test
    fun `apk report percent divides by total`() {
        val bucket = SizeBucket("dex", 250L)
        val report = ApkReport(File("a.apk"), 1000L, listOf(bucket), emptyList())
        assertEquals(25.0, report.percent(bucket), 0.001)
        assertEquals(0.0, ApkReport(File("a.apk"), 0L, listOf(bucket), emptyList())
            .percent(bucket), 0.001)
    }

    @Test
    fun `size bucket defaults to zero bytes`() {
        assertEquals(0L, SizeBucket("other").bytes)
    }
}
