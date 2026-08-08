package com.maodouchat.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentProgressPolicyTest {

    @Test
    fun `invalid total yields null`() {
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, 0, 0f, 0.5f, -1f))
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, -1, 0f, 0.5f, -1f))
    }

    @Test
    fun `maps into segment`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0.2f, 0.8f, -1f)
        assertEquals(0.5f, p!!, 0.0001f)
    }

    @Test
    fun `suppresses tiny deltas before complete`() {
        val first = AttachmentProgressPolicy.mapSegmentProgress(10, 100, 0f, 1f, -1f)!!
        assertNull(
            AttachmentProgressPolicy.mapSegmentProgress(10, 100, 0f, 1f, first)
        )
        val next = AttachmentProgressPolicy.mapSegmentProgress(20, 100, 0f, 1f, first)
        assertTrue(next != null && next > first)
    }

    @Test
    fun `always publishes completion`() {
        val prev = 0.99f
        val done = AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0f, 1f, prev)
        assertEquals(1f, done!!, 0.0001f)
    }
}
