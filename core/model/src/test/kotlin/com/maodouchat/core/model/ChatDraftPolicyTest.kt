package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftPolicyTest {

    @Test
    fun `schedule and write gates match owner and chat`() {
        assertEquals(350L, ChatDraftPolicy.SAVE_DELAY_MS)
        assertFalse(ChatDraftPolicy.canSchedule(null, "c1"))
        assertFalse(ChatDraftPolicy.canSchedule("u1", ""))
        assertTrue(ChatDraftPolicy.canSchedule("u1", "c1"))
        assertTrue(ChatDraftPolicy.shouldPersistGeneration(3, 3))
        assertFalse(ChatDraftPolicy.shouldPersistGeneration(3, 4))
        assertFalse(ChatDraftPolicy.shouldWrite("u1", "u2"))
        assertTrue(ChatDraftPolicy.shouldWrite("u1", "u1"))
    }

    @Test
    fun `blank clears and restore does not stomp edits`() {
        assertTrue(ChatDraftPolicy.isClearRequest("  \n"))
        assertFalse(ChatDraftPolicy.isClearRequest("hello"))
        assertTrue(ChatDraftPolicy.shouldApplyRestoredDraft(false, ""))
        assertFalse(ChatDraftPolicy.shouldApplyRestoredDraft(true, ""))
        assertFalse(ChatDraftPolicy.shouldApplyRestoredDraft(false, "typing"))
    }
}
