package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncCursorPolicyTest {

    @Test
    fun `later timestamp is strictly after`() {
        assertTrue(
            SyncCursorPolicy.isStrictlyAfter(
                SyncCursor(timestampMs = 200L, messageId = "a"),
                SyncCursor(timestampMs = 100L, messageId = "z")
            )
        )
    }

    @Test
    fun `earlier timestamp is not strictly after`() {
        assertFalse(
            SyncCursorPolicy.isStrictlyAfter(
                SyncCursor(timestampMs = 100L, messageId = "z"),
                SyncCursor(timestampMs = 200L, messageId = "a")
            )
        )
    }

    @Test
    fun `same timestamp greater message id is strictly after`() {
        assertTrue(
            SyncCursorPolicy.isStrictlyAfter(
                SyncCursor(timestampMs = 100L, messageId = "b"),
                SyncCursor(timestampMs = 100L, messageId = "a")
            )
        )
    }

    @Test
    fun `same timestamp smaller message id is not strictly after`() {
        assertFalse(
            SyncCursorPolicy.isStrictlyAfter(
                SyncCursor(timestampMs = 100L, messageId = "a"),
                SyncCursor(timestampMs = 100L, messageId = "b")
            )
        )
    }

    @Test
    fun `identical cursor is not strictly after`() {
        assertFalse(
            SyncCursorPolicy.isStrictlyAfter(
                SyncCursor(timestampMs = 100L, messageId = "a"),
                SyncCursor(timestampMs = 100L, messageId = "a")
            )
        )
    }
}
