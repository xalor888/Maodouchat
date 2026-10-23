package com.maodouchat.ui.screen.chatdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftPolicyTest {

    @Test
    fun scheduleRequiresOwnerAndChat() {
        assertFalse(ChatDraftPolicy.canSchedule(null, "c1"))
        assertFalse(ChatDraftPolicy.canSchedule("u1", ""))
        assertTrue(ChatDraftPolicy.canSchedule("u1", "c1"))
    }

    @Test
    fun generationAndAccountGates() {
        assertTrue(ChatDraftPolicy.shouldPersistGeneration(3, 3))
        assertFalse(ChatDraftPolicy.shouldPersistGeneration(3, 4))
        assertFalse(ChatDraftPolicy.shouldWrite("u1", "u2"))
        assertTrue(ChatDraftPolicy.shouldWrite("u1", "u1"))
    }

    @Test
    fun blankClearsAndRestoreDoesNotStompEdits() {
        assertTrue(ChatDraftPolicy.isClearRequest("  \n"))
        assertFalse(ChatDraftPolicy.isClearRequest("hello"))
        assertTrue(ChatDraftPolicy.shouldApplyRestoredDraft(false, ""))
        assertFalse(ChatDraftPolicy.shouldApplyRestoredDraft(true, ""))
        assertFalse(ChatDraftPolicy.shouldApplyRestoredDraft(false, "typing"))
    }

    @Test
    fun delayConstantMatchesPriorBehavior() {
        assertEquals(350L, ChatDraftPolicy.SAVE_DELAY_MS)
    }

    @Test
    fun canScheduleRejectsBlanksAndNullsInEveryPosition() {
        assertFalse(ChatDraftPolicy.canSchedule(" ", "c1"))
        assertFalse(ChatDraftPolicy.canSchedule("u1", null))
        assertFalse(ChatDraftPolicy.canSchedule(null, null))
        assertFalse(ChatDraftPolicy.canSchedule("  ", "  "))
        assertTrue(ChatDraftPolicy.canSchedule("u1", "c1"))
    }

    @Test
    fun stragglerGenerationIsDroppedRegardlessOfDirection() {
        assertFalse(ChatDraftPolicy.shouldPersistGeneration(5, 3))
        assertFalse(ChatDraftPolicy.shouldPersistGeneration(2, 9))
        assertTrue(ChatDraftPolicy.shouldPersistGeneration(0, 0))
    }

    @Test
    fun blankOwnerNeverWritesEvenWhenItMatches() {
        assertFalse(ChatDraftPolicy.shouldWrite("", ""))
        assertFalse(ChatDraftPolicy.shouldWrite("u1", " "))
        assertTrue(ChatDraftPolicy.shouldWrite("u1", "u1"))
    }

    @Test
    fun emptyStringIsAClearRequest() {
        assertTrue(ChatDraftPolicy.isClearRequest(""))
        assertTrue(ChatDraftPolicy.isClearRequest("\t"))
        assertFalse(ChatDraftPolicy.isClearRequest(" x "))
    }

    @Test
    fun restoredDraftIsRefusedOnceTheUserEditedAnything() {
        assertFalse(ChatDraftPolicy.shouldApplyRestoredDraft(true, "typing"))
        assertTrue(ChatDraftPolicy.shouldApplyRestoredDraft(false, ""))
    }
}
