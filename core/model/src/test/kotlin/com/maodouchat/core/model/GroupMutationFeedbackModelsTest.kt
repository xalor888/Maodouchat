package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMutationFeedbackModelsTest {

    @Test
    fun `success feedback keeps defaults`() {
        val fb = GroupMutationFeedback(
            kind = GroupMutationFeedbackKind.SUCCESS,
            action = GroupMutationAction.RENAME
        )
        assertEquals(GroupMutationFeedbackKind.SUCCESS, fb.kind)
        assertEquals(GroupMutationAction.RENAME, fb.action)
        assertNull(fb.detail)
        assertNull(fb.serverCode)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `retryable error carries detail and retry flag`() {
        val fb = GroupMutationFeedback(
            kind = GroupMutationFeedbackKind.ERROR_RETRYABLE,
            action = GroupMutationAction.MUTE,
            detail = "offline",
            canRetry = true
        )
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertEquals("offline", fb.detail)
    }

    @Test
    fun `permission error forces reload without retry`() {
        val fb = GroupMutationFeedback(
            kind = GroupMutationFeedbackKind.ERROR_PERMISSION,
            action = GroupMutationAction.TRANSFER_OWNER,
            serverCode = "GROUP_PERMISSION_DENIED",
            shouldReload = true
        )
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_PERMISSION_DENIED", fb.serverCode)
    }

    @Test
    fun `action enum covers group management operations`() {
        assertEquals(13, GroupMutationAction.entries.size)
        assertEquals(5, GroupMutationFeedbackKind.entries.size)
    }
}
