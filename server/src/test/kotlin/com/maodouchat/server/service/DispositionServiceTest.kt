package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DispositionServiceTest {

    @Test
    fun `templates include other requiring note`() {
        val other = DispositionService.findTemplate("other")
        assertTrue(other != null && other.requiresCustomNote)
        assertTrue(DispositionService.banReasonTemplates.size >= 5)
        assertTrue(DispositionService.APPEAL_NOTICE_ZH.contains("申诉"))
    }

    @Test
    fun `ban requires known reason`() {
        val missing = DispositionService.validateDisposition(7, null, null)
        assertIs<DispositionService.DispositionValidation.Invalid>(missing)

        val ok = DispositionService.validateDisposition(7, "spam", null)
        assertIs<DispositionService.DispositionValidation.Ok>(ok)
        assertEquals("spam", ok.reasonCode)
        assertEquals(7, ok.banDays)
    }

    @Test
    fun `other requires custom note`() {
        val noNote = DispositionService.validateDisposition(1, "other", "  ")
        assertIs<DispositionService.DispositionValidation.Invalid>(noNote)

        val withNote = DispositionService.validateDisposition(1, "other", "  运营复核  ")
        assertIs<DispositionService.DispositionValidation.Ok>(withNote)
        assertEquals("运营复核", withNote.note)
    }

    @Test
    fun `unban accepts empty reason`() {
        val ok = DispositionService.validateDisposition(0, null, "误封")
        assertIs<DispositionService.DispositionValidation.Ok>(ok)
        assertEquals(DispositionService.unbanReasonCode, ok.reasonCode)
        assertEquals("误封", ok.note)
    }

    @Test
    fun `days bounds`() {
        assertIs<DispositionService.DispositionValidation.Invalid>(
            DispositionService.validateDisposition(-1, "spam", null)
        )
        assertIs<DispositionService.DispositionValidation.Invalid>(
            DispositionService.validateDisposition(DispositionService.MAX_BAN_DAYS + 1, "spam", null)
        )
    }

    @Test
    fun `audit detail is metadata only`() {
        val detail = DispositionService.auditDetail(
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
        assertEquals(0L, DispositionService.bannedUntilFromDays(0, now))
        assertEquals(now + 7L * 86_400_000L, DispositionService.bannedUntilFromDays(7, now))
    }

    @Test
    fun `mute templates validate hours and notes`() {
        assertTrue(DispositionService.muteReasonTemplates.isNotEmpty())
        val ok = DispositionService.validateMute(1, "flood", null)
        assertIs<DispositionService.DispositionValidation.Ok>(ok)
        assertEquals("flood", ok.reasonCode)

        val needNote = DispositionService.validateMute(1, "other", null)
        assertIs<DispositionService.DispositionValidation.Invalid>(needNote)

        val unmute = DispositionService.validateMute(0, null, null)
        assertIs<DispositionService.DispositionValidation.Ok>(unmute)
        assertEquals(DispositionService.unmuteReasonCode, unmute.reasonCode)

        val now = 2_000_000L
        assertEquals(now + 3_600_000L, DispositionService.mutedUntilFromHours(1, now))
        assertEquals(0L, DispositionService.mutedUntilFromHours(0, now))
    }

    @Test
    fun `post restrict templates validate days and notes`() {
        assertTrue(DispositionService.postRestrictReasonTemplates.isNotEmpty())
        val ok = DispositionService.validatePostRestrict(3, "spam_feed", null)
        assertIs<DispositionService.DispositionValidation.Ok>(ok)
        assertEquals("spam_feed", ok.reasonCode)
        assertEquals(3, ok.banDays)

        val needNote = DispositionService.validatePostRestrict(1, "other", null)
        assertIs<DispositionService.DispositionValidation.Invalid>(needNote)

        val withNote = DispositionService.validatePostRestrict(1, "other", "  复核  ")
        assertIs<DispositionService.DispositionValidation.Ok>(withNote)
        assertEquals("复核", withNote.note)

        val clear = DispositionService.validatePostRestrict(0, null, null)
        assertIs<DispositionService.DispositionValidation.Ok>(clear)
        assertEquals(DispositionService.unrestrictPostsReasonCode, clear.reasonCode)

        assertIs<DispositionService.DispositionValidation.Invalid>(
            DispositionService.validatePostRestrict(-1, "spam_feed", null)
        )
        assertIs<DispositionService.DispositionValidation.Invalid>(
            DispositionService.validatePostRestrict(
                DispositionService.MAX_POST_RESTRICT_DAYS + 1,
                "spam_feed",
                null
            )
        )

        val now = 3_000_000L
        assertEquals(0L, DispositionService.postRestrictedUntilFromDays(0, now))
        assertEquals(now + 7L * 86_400_000L, DispositionService.postRestrictedUntilFromDays(7, now))

        val detail = DispositionService.auditPostRestrictDetail(
            postRestrictedUntil = 1_800_000_000_000L,
            reasonCode = "scam_feed",
            note = "工单#9"
        )
        assertTrue(detail.contains("postRestrictedUntil="))
        assertTrue(detail.contains("reasonCode=scam_feed"))
        assertTrue(detail.contains("note=工单#9"))
    }
}
