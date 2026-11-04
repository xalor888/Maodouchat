package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatComposerSendModelsTest {

    @Test
    fun `composer snapshot carries all flags`() {
        val snapshot = ComposerStateSnapshot(
            attachMenuVisible = true,
            expressionPanelVisible = false,
            expressionMode = "STICKER",
            aiMenuVisible = false,
            translationLanguagesVisible = true,
            quickPhrasesVisible = false,
            contactCardPickerVisible = true,
        )
        assertEquals(true, snapshot.attachMenuVisible)
        assertEquals("STICKER", snapshot.expressionMode)
        assertEquals(true, snapshot.translationLanguagesVisible)
    }

    @Test
    fun `history load plan carries fields and copy keeps the rest`() {
        val plan = HistoryLoadPlan("m1", true, false, "m2", 7L)
        assertEquals("m1", plan.unreadSeparatorId)
        assertEquals("m2", plan.readReceiptThroughMessageId)
        assertEquals(7L, plan.readReceiptGroupRevision)
        val copied = plan.copy(enqueueReadReceipt = true)
        assertEquals(true, copied.armSecretDisappearing)
        assertEquals(true, copied.enqueueReadReceipt)
    }

    @Test
    fun `nudge allow and reject are distinct decisions`() {
        val allow = NudgeDecision.Allow("u1", "c1")
        val reject = NudgeDecision.Reject(NudgeRejectReason.DISABLED)
        assertNotEquals(allow, reject)
        assertEquals("u1", allow.ownerUserId)
        assertEquals(NudgeRejectReason.DISABLED, reject.reason)
    }

    @Test
    fun `all four reject reasons are distinct`() {
        val reasons = NudgeRejectReason.entries
        assertEquals(4, reasons.size)
        assertEquals(4, reasons.distinct().size)
    }
}
