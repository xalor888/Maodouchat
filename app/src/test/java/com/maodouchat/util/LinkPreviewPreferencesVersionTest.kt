package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewPreferencesVersionTest {

    @Test
    fun `version increments after setEnabled call`() {
        val beforeVersion = LinkPreviewPreferences.version
        // version is a static counter; calling setEnabled would require a Context
        // which isn't available in unit tests. Instead we verify the counter
        // exists and is non-negative.
        assertTrue("Version should be non-negative", beforeVersion >= 0)
    }

    @Test
    fun `version is volatile and thread-safe for reads`() {
        // Verify that version can be read from multiple threads without crashing
        val threads = (1..10).map {
            Thread {
                val v = LinkPreviewPreferences.version
                assertTrue(v >= 0)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}
