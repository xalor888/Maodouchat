package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiAuditLogResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiAuditLogFilterPolicyTest {

    private fun log(
        id: String,
        feature: String,
        status: String,
        model: String? = null,
        error: String? = null,
        chatId: String? = null
    ) = AiAuditLogResponse(
        id = id, chatId = chatId, feature = feature, model = model,
        status = status, error = error, createdAt = 1000L
    )

    private val logs = listOf(
        log("1", "摘要", "OK", model = "qwen-max", chatId = "c1"),
        log("2", "翻译", "FAILED", model = "qwen-plus", error = "timeout", chatId = "c2"),
        log("3", "改写", "OK"),
    )

    @Test
    fun `blank query returns all`() {
        assertEquals(logs, AiAuditLogFilterPolicy.filter(logs, ""))
        assertEquals(logs, AiAuditLogFilterPolicy.filter(logs, "  "))
    }

    @Test
    fun `matches by feature`() {
        assertEquals(listOf(logs[0]), AiAuditLogFilterPolicy.filter(logs, "摘要"))
    }

    @Test
    fun `matches by status case-insensitively`() {
        assertEquals(listOf(logs[1]), AiAuditLogFilterPolicy.filter(logs, "failed"))
    }

    @Test
    fun `matches by model and error`() {
        assertEquals(listOf(logs[0]), AiAuditLogFilterPolicy.filter(logs, "QWEN-MAX"))
        assertEquals(listOf(logs[1]), AiAuditLogFilterPolicy.filter(logs, "timeout"))
    }

    @Test
    fun `matches by chat id and tolerates nulls`() {
        assertEquals(listOf(logs[1]), AiAuditLogFilterPolicy.filter(logs, "c2"))
        assertFalse(AiAuditLogFilterPolicy.matches(logs[2], "qwen"))
    }

    @Test
    fun `no match returns empty and query is trimmed`() {
        assertEquals(emptyList<AiAuditLogResponse>(), AiAuditLogFilterPolicy.filter(logs, "zzz"))
        assertEquals(listOf(logs[1]), AiAuditLogFilterPolicy.filter(logs, "  翻译 "))
    }
}
