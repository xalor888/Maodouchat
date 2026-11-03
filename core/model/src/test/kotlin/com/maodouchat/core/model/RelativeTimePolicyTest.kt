package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeTimePolicyTest {

    @Test
    fun `past under one minute uses just now`() {
        assertTrue(RelativeTimePolicy.shouldUseJustNow(1_000_000L, 1_059_000L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(1_000_000L, 1_060_000L))
    }

    @Test
    fun `future within grace uses just now`() {
        assertTrue(RelativeTimePolicy.shouldUseJustNow(1_000_000L, 881_000L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(1_000_000L, 880_000L))
    }

    @Test
    fun `non-positive timestamp never uses just now`() {
        assertFalse(RelativeTimePolicy.shouldUseJustNow(0L, 1_000_000L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(-1L, 1_000_000L))
    }
}
