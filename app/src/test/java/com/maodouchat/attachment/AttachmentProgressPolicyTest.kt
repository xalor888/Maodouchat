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

    @Test
    fun `clamps segment bounds into range`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(50, 100, -0.5f, 1.5f, -1f)
        assertEquals(0.5f, p!!, 0.0001f)
    }

    @Test
    fun `reversed segment pins to start`() {
        val mid = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0.8f, 0.2f, -1f)
        assertEquals(0.8f, mid!!, 0.0001f)
        val done = AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0.8f, 0.2f, -1f)
        assertEquals(0.8f, done!!, 0.0001f)
    }

    @Test
    fun `byte ratio clamped at bounds`() {
        val over = AttachmentProgressPolicy.mapSegmentProgress(150, 100, 0f, 1f, -1f)
        assertEquals(1f, over!!, 0.0001f)
        val under = AttachmentProgressPolicy.mapSegmentProgress(-10, 100, 0f, 1f, -1f)
        assertEquals(0f, under!!, 0.0001f)
    }

    @Test
    fun `deeply negative previous counts as unset`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(10, 100, 0f, 1f, -5f)
        assertEquals(0.1f, p!!, 0.0001f)
    }

    @Test
    fun `zero length segment pins to start`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0.3f, 0.3f, -1f)
        assertEquals(0.3f, p!!, 0.0001f)
    }

    @Test
    fun `above threshold delta publishes`() {
        val first = AttachmentProgressPolicy.mapSegmentProgress(10, 100, 0f, 1f, -1f)!!
        val next = AttachmentProgressPolicy.mapSegmentProgress(12, 100, 0f, 1f, first)
        assertEquals(0.12f, next!!, 0.0001f)
    }

    @Test
    fun `sub threshold delta inside segment suppressed`() {
        val first = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0f, 1f, -1f)!!
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(505, 1000, 0f, 1f, first))
    }

    @Test
    fun `completion publishes from zero previous`() {
        val done = AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0f, 1f, 0f)
        assertEquals(1f, done!!, 0.0001f)
    }

    @Test
    fun `segment fully above one collapses to one`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 1.5f, 2f, -1f)
        assertEquals(1f, p!!, 0.0001f)
    }

    @Test
    fun `zero completed maps to segment start`() {
        val p = AttachmentProgressPolicy.mapSegmentProgress(0, 100, 0.2f, 0.8f, -1f)
        assertEquals(0.2f, p!!, 0.0001f)
    }
}
