package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminAiAuditPolicyTest {

    @Test
    fun `token estimate is soft and non-zero for non-empty input`() {
        assertEquals(0, AdminAiAuditPolicy.estimatedTokensFromInputChars(0))
        assertEquals(1, AdminAiAuditPolicy.estimatedTokensFromInputChars(1))
        assertEquals(25, AdminAiAuditPolicy.estimatedTokensFromInputChars(100))
    }

    @Test
    fun `admin projection is metadata-only with token estimate`() {
        val row = AdminAiAuditPolicy.toAdminResponse(
            id = "ai_1",
            userId = "u1",
            feature = "rewrite",
            model = "gpt-test",
            status = "success",
            inputChars = 120,
            contextMessages = 3,
            durationMs = 80L,
            error = null,
            createdAt = 1_700_000_000_000L
        )
        assertEquals("u1", row.userId)
        assertEquals("rewrite", row.feature)
        assertEquals("gpt-test", row.model)
        assertEquals(120, row.inputChars)
        assertEquals(30, row.estimatedTokens)
        assertEquals(80L, row.durationMs)
        assertNull(row.error)
        val allowedKeys = setOf(
            "id", "userId", "feature", "model", "status",
            "inputChars", "contextMessages", "durationMs", "error", "createdAt", "estimatedTokens"
        )
        assertTrue(AdminAiAuditPolicy.isMetadataOnlyResponse(allowedKeys))
        assertFalse(
            AdminAiAuditPolicy.isMetadataOnlyResponse(allowedKeys + "prompt")
        )
        assertFalse(
            AdminAiAuditPolicy.isMetadataOnlyResponse(allowedKeys + "chatId")
        )
    }

    @Test
    fun `filters normalize blanks and ALL`() {
        assertNull(AdminAiAuditPolicy.normalizeFeatureFilter("  ALL "))
        assertNull(AdminAiAuditPolicy.normalizeFeatureFilter("   "))
        assertEquals("rewrite", AdminAiAuditPolicy.normalizeFeatureFilter(" rewrite "))
        assertEquals(60, AdminAiAuditPolicy.normalizeLimit(null))
        assertEquals(250, AdminAiAuditPolicy.normalizeLimit(9999))
        assertEquals(0L, AdminAiAuditPolicy.normalizeOffset(-5))
    }
}
