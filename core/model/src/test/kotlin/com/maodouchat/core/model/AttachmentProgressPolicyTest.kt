package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentProgressPolicyTest {

    @Test
    fun `invalid total yields null`() {
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, 0, 0f, 0.5f, -1f))
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, -1, 0f, 0.5f, -1f))
    }

    @Test
    fun `segment midpoint maps into sub-range`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0.2f, 0.8f, -1f)
        assertEquals(0.5f, p!!, 0.0001f)
    }

    @Test
    fun `sub-percent delta is suppressed`() {
        val first = AttachmentProgressPolicy.mapSegmentProgress(10, 100, 0f, 1f, -1f)!!
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(105, 1000, 0f, 1f, first))
    }

    @Test
    fun `completion is published despite small delta`() {
        val done = AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0f, 1f, 0.995f)
        assertEquals(1f, done!!, 0.0001f)
    }
}
