package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationReportPolicyTest {

    @Test
    fun `pending check is case insensitive`() {
        assertTrue(isModerationReportPending("PENDING"))
        assertTrue(isModerationReportPending("Pending"))
        assertTrue(isModerationReportPending("pending"))
    }

    @Test
    fun `non pending statuses are not pending`() {
        assertFalse(isModerationReportPending("RESOLVED"))
        assertFalse(isModerationReportPending("REJECTED"))
        assertFalse(isModerationReportPending(""))
    }

    @Test
    fun `message post and comment targets can delete content`() {
        assertTrue(moderationReportTargetCanDeleteContent("MESSAGE"))
        assertTrue(moderationReportTargetCanDeleteContent("POST"))
        assertTrue(moderationReportTargetCanDeleteContent("COMMENT"))
    }

    @Test
    fun `other targets cannot delete content`() {
        assertFalse(moderationReportTargetCanDeleteContent("USER"))
        assertFalse(moderationReportTargetCanDeleteContent("MESSAGE_META"))
        assertFalse(moderationReportTargetCanDeleteContent("ALL"))
        assertFalse(moderationReportTargetCanDeleteContent(""))
    }
}
