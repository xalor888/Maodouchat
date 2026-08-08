package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdminDispositionPolicyTest {

    @Test
    fun `templates include other requiring note`() {
        val other = AdminDispositionPolicy.findTemplate("other")
        assertTrue(other != null && other.requiresCustomNote)
        assertTrue(AdminDispositionPolicy.banReasonTemplates.size >= 5)
        assertTrue(AdminDispositionPolicy.APPEAL_NOTICE_ZH.contains("申诉"))
    }

    @Test
    fun `ban requires known reason`() {
        val missing = AdminDispositionPolicy.validateDisposition(7, null, null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(missing)

        val ok = AdminDispositionPolicy.validateDisposition(7, "spam", null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(ok)
        assertEquals("spam", ok.reasonCode)
        assertEquals(7, ok.banDays)
    }

    @Test
    fun `other requires custom note`() {
        val noNote = AdminDispositionPolicy.validateDisposition(1, "other", "  ")
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(noNote)

        val withNote = AdminDispositionPolicy.validateDisposition(1, "other", "  运营复核  ")
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(withNote)
        assertEquals("运营复核", withNote.note)
    }

    @Test
    fun `unban accepts empty reason`() {
        val ok = AdminDispositionPolicy.validateDisposition(0, null, "误封")
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(ok)
        assertEquals(AdminDispositionPolicy.unbanReasonCode, ok.reasonCode)
        assertEquals("误封", ok.note)
    }

    @Test
    fun `days bounds`() {
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(
            AdminDispositionPolicy.validateDisposition(-1, "spam", null)
        )
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(
            AdminDispositionPolicy.validateDisposition(AdminDispositionPolicy.MAX_BAN_DAYS + 1, "spam", null)
        )
    }

    @Test
    fun `audit detail is metadata only`() {
        val detail = AdminDispositionPolicy.auditDetail(
            bannedUntil = 1_700_000_000_000L,
            reasonCode = "scam",
            note = "证据工单#12"
        )
        assertTrue(detail.contains("reasonCode=scam"))
        assertTrue(detail.contains("note=证据工单#12"))
        assertTrue(!detail.contains("prompt"))
    }

    @Test
    fun `banned until from days`() {
        val now = 1_000_000L
        assertEquals(0L, AdminDispositionPolicy.bannedUntilFromDays(0, now))
        assertEquals(now + 7L * 86_400_000L, AdminDispositionPolicy.bannedUntilFromDays(7, now))
    }

    @Test
    fun `mute templates validate hours and notes`() {
        assertTrue(AdminDispositionPolicy.muteReasonTemplates.isNotEmpty())
        val ok = AdminDispositionPolicy.validateMute(1, "flood", null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(ok)
        assertEquals("flood", ok.reasonCode)

        val needNote = AdminDispositionPolicy.validateMute(1, "other", null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(needNote)

        val unmute = AdminDispositionPolicy.validateMute(0, null, null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(unmute)
        assertEquals(AdminDispositionPolicy.unmuteReasonCode, unmute.reasonCode)

        val now = 2_000_000L
        assertEquals(now + 3_600_000L, AdminDispositionPolicy.mutedUntilFromHours(1, now))
        assertEquals(0L, AdminDispositionPolicy.mutedUntilFromHours(0, now))
    }

    @Test
    fun `post restrict templates validate days and notes`() {
        assertTrue(AdminDispositionPolicy.postRestrictReasonTemplates.isNotEmpty())
        val ok = AdminDispositionPolicy.validatePostRestrict(3, "spam_feed", null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(ok)
        assertEquals("spam_feed", ok.reasonCode)
        assertEquals(3, ok.banDays)

        val needNote = AdminDispositionPolicy.validatePostRestrict(1, "other", null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(needNote)

        val withNote = AdminDispositionPolicy.validatePostRestrict(1, "other", "  复核  ")
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(withNote)
        assertEquals("复核", withNote.note)

        val clear = AdminDispositionPolicy.validatePostRestrict(0, null, null)
        assertIs<AdminDispositionPolicy.DispositionValidation.Ok>(clear)
        assertEquals(AdminDispositionPolicy.unrestrictPostsReasonCode, clear.reasonCode)

        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(
            AdminDispositionPolicy.validatePostRestrict(-1, "spam_feed", null)
        )
        assertIs<AdminDispositionPolicy.DispositionValidation.Invalid>(
            AdminDispositionPolicy.validatePostRestrict(
                AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS + 1,
                "spam_feed",
                null
            )
        )

        val now = 3_000_000L
        assertEquals(0L, AdminDispositionPolicy.postRestrictedUntilFromDays(0, now))
        assertEquals(now + 7L * 86_400_000L, AdminDispositionPolicy.postRestrictedUntilFromDays(7, now))

        val detail = AdminDispositionPolicy.auditPostRestrictDetail(
            postRestrictedUntil = 1_800_000_000_000L,
            reasonCode = "scam_feed",
            note = "工单#9"
        )
        assertTrue(detail.contains("postRestrictedUntil="))
        assertTrue(detail.contains("reasonCode=scam_feed"))
        assertTrue(detail.contains("note=工单#9"))
    }
}
