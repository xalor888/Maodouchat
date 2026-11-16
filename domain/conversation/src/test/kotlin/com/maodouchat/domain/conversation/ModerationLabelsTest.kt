package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ModerationLabelsTest {

    @Test
    fun `status kinds cover all known statuses`() {
        val known = mapOf(
            "OPEN" to ModerationReportStatusKind.Open,
            "IN_REVIEW" to ModerationReportStatusKind.InReview,
            "RESOLVED" to ModerationReportStatusKind.Resolved,
            "REJECTED" to ModerationReportStatusKind.Rejected,
            "ALL" to ModerationReportStatusKind.All,
        )
        known.forEach { (status, expected) ->
            assertEquals(expected, moderationReportStatusKind(status))
        }
    }

    @Test
    fun `unknown status is raw`() {
        assertEquals(ModerationReportStatusKind.Raw, moderationReportStatusKind("PENDING"))
        assertEquals(ModerationReportStatusKind.Raw, moderationReportStatusKind(""))
        // 精确匹配：小写走不到已知分支
        assertEquals(ModerationReportStatusKind.Raw, moderationReportStatusKind("open"))
    }

    @Test
    fun `action kinds cover all known actions`() {
        val known = mapOf(
            "DELETE_CONTENT" to ModerationReportActionKind.DeleteContent,
            "NO_ACTION" to ModerationReportActionKind.NoAction,
            "RESTRICT_MESSAGES_24H" to ModerationReportActionKind.RestrictMessages24h,
            "RESTRICT_POSTS_7D" to ModerationReportActionKind.RestrictPosts7d,
            "SUSPEND_24H" to ModerationReportActionKind.Suspend24h,
        )
        known.forEach { (action, expected) ->
            assertEquals(expected, moderationReportActionKind(action))
        }
    }

    @Test
    fun `unknown action is raw`() {
        assertEquals(ModerationReportActionKind.Raw, moderationReportActionKind("BAN_USER"))
        assertEquals(ModerationReportActionKind.Raw, moderationReportActionKind(""))
    }

    @Test
    fun `risk action kinds cover all known actions`() {
        val known = mapOf(
            "WARN_MOD" to ModerationRiskActionKind.WarnMod,
            "AUTO_HOLD" to ModerationRiskActionKind.AutoHold,
            "AUTO_DELETE" to ModerationRiskActionKind.AutoDelete,
            "AUTO_RATE_LIMIT" to ModerationRiskActionKind.AutoRateLimit,
            "OBSERVED" to ModerationRiskActionKind.Observed,
        )
        known.forEach { (action, expected) ->
            assertEquals(expected, moderationRiskActionKind(action))
        }
    }

    @Test
    fun `unknown risk action is raw`() {
        assertEquals(ModerationRiskActionKind.Raw, moderationRiskActionKind("AUTO_BAN"))
        assertEquals(ModerationRiskActionKind.Raw, moderationRiskActionKind(""))
    }

    @Test
    fun `target kinds cover all known types`() {
        val known = mapOf(
            "USER" to ModerationReportTargetKind.User,
            "MESSAGE" to ModerationReportTargetKind.Message,
            "MESSAGE_META" to ModerationReportTargetKind.MessageMeta,
            "POST" to ModerationReportTargetKind.Post,
            "COMMENT" to ModerationReportTargetKind.Comment,
            "ALL" to ModerationReportTargetKind.All,
        )
        known.forEach { (type, expected) ->
            assertEquals(expected, moderationReportTargetKind(type))
        }
    }

    @Test
    fun `unknown target type is raw`() {
        assertEquals(ModerationReportTargetKind.Raw, moderationReportTargetKind("GROUP"))
        assertEquals(ModerationReportTargetKind.Raw, moderationReportTargetKind(""))
    }
}
