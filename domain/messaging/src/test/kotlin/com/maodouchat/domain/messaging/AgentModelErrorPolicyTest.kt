package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentModelErrorPolicyTest {

    @Test
    fun `formatHttpError keeps short payload`() {
        assertEquals(
            "模型接口 500: boom",
            AgentModelErrorPolicy.formatHttpError("模型接口", 500, "boom", "Server Error"),
        )
    }

    @Test
    fun `formatHttpError truncates payload at 240`() {
        val payload = "x".repeat(300)
        assertEquals(
            "模型接口 400: ${"x".repeat(240)}",
            AgentModelErrorPolicy.formatHttpError("模型接口", 400, payload, "Bad Request"),
        )
    }

    @Test
    fun `formatHttpError falls back to response message on blank payload`() {
        assertEquals(
            "语音接口 503: Service Unavailable",
            AgentModelErrorPolicy.formatHttpError("语音接口", 503, "   ", "Service Unavailable"),
        )
        assertEquals(
            "语音接口 503: Service Unavailable",
            AgentModelErrorPolicy.formatHttpError("语音接口", 503, "", "Service Unavailable"),
        )
    }
}
