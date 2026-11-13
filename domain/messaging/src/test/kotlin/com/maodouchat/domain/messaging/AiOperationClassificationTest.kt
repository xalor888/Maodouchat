package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.core.model.AiRetryPolicyCategory
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.PendingAiAction
import kotlin.test.Test
import kotlin.test.assertEquals

// 从 app 侧 ChatAiOperationModelTest 随迁：分类断言逐字不变，错误码改用字面量。
class AiOperationClassificationTest {
    @Test
    fun `all ai actions have a shared light or heavy invocation category`() {
        assertEquals(AiRetryPolicyCategory.LIGHT, PendingAiAction.Rewrite("formal").invocationCategory())
        assertEquals(AiRetryPolicyCategory.LIGHT, PendingAiAction.SuggestReplies().invocationCategory())
        assertEquals(
            AiRetryPolicyCategory.LIGHT,
            PendingAiAction.SemanticSearch("query", listOf("m1")).invocationCategory()
        )
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR).invocationCategory()
        )
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null).invocationCategory()
        )
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
            PendingAiAction.GroupAssistant("query", "answer").invocationCategory()
        )
    }

    @Test
    fun `only proven connection failure is classified as safe for retry`() {
        assertEquals(
            "CONNECTION_NOT_ESTABLISHED",
            classifyAiOperationError(
                ApiException(ApiFailureKind.NETWORK, requestMayHaveReachedServer = false)
            )
        )
        listOf(
            ApiException(ApiFailureKind.NETWORK, requestMayHaveReachedServer = true),
            ApiException(ApiFailureKind.TIMEOUT),
            ApiException(ApiFailureKind.INVALID_RESPONSE),
            ApiException(ApiFailureKind.UNEXPECTED)
        ).forEach { error ->
            assertEquals("OUTCOME_UNKNOWN", classifyAiOperationError(error))
        }
        assertEquals(
            "SERVER",
            classifyAiOperationError(ApiException(ApiFailureKind.HTTP, statusCode = 503))
        )
        assertEquals("UNKNOWN", classifyAiOperationError(IllegalStateException("broken")))
    }

    @Test
    fun `http 429 and rate_limited codes classify as rate limited with wait`() {
        assertEquals(
            "RATE_LIMITED",
            classifyAiOperationError(
                ApiException(
                    ApiFailureKind.HTTP,
                    statusCode = 429,
                    serverMessage = "AI 请求过于频繁",
                    retryAfterSeconds = 40
                )
            )
        )
        assertEquals(
            "RATE_LIMITED",
            classifyAiOperationError(
                ApiException(
                    ApiFailureKind.HTTP,
                    statusCode = 503,
                    serverCode = "rate_limited"
                )
            )
        )
        assertEquals(
            "QUOTA_EXCEEDED",
            classifyAiOperationError(
                ApiException(
                    ApiFailureKind.HTTP,
                    statusCode = 403,
                    serverCode = "QUOTA_EXCEEDED"
                )
            )
        )
        val detailed = classifyAiOperationErrorDetailed(
            ApiException(
                ApiFailureKind.HTTP,
                statusCode = 429,
                serverMessage = "AI 请求过于频繁",
                retryAfterSeconds = 40
            )
        )
        assertEquals("RATE_LIMITED", detailed.code)
        assertEquals(40L, detailed.retryAfterSeconds)
        assertEquals("RATE_LIMITED:40", detailed.persistedCode)
    }

    @Test
    fun `detailed server failure without retry hint carries no wait`() {
        val detailed = classifyAiOperationErrorDetailed(
            ApiException(ApiFailureKind.HTTP, statusCode = 503)
        )

        assertEquals("SERVER", detailed.code)
        assertEquals(null, detailed.retryAfterSeconds)
    }

    @Test
    fun `detailed quota failure without retry hint falls back to default wait`() {
        val detailed = classifyAiOperationErrorDetailed(
            ApiException(ApiFailureKind.HTTP, statusCode = 403, serverCode = "QUOTA_EXCEEDED")
        )

        assertEquals("QUOTA_EXCEEDED", detailed.code)
        assertEquals(300L, detailed.retryAfterSeconds)
        assertEquals("QUOTA_EXCEEDED:300", detailed.persistedCode)
    }

    @Test
    fun `detailed non-retryable failures carry no wait and a bare persisted code`() {
        val connection = classifyAiOperationErrorDetailed(
            ApiException(ApiFailureKind.NETWORK, requestMayHaveReachedServer = false)
        )
        assertEquals("CONNECTION_NOT_ESTABLISHED", connection.code)
        assertEquals(null, connection.retryAfterSeconds)
        assertEquals("CONNECTION_NOT_ESTABLISHED", connection.persistedCode)

        val timeout = classifyAiOperationErrorDetailed(ApiException(ApiFailureKind.TIMEOUT))
        assertEquals("OUTCOME_UNKNOWN", timeout.code)
        assertEquals(null, timeout.retryAfterSeconds)
        assertEquals("OUTCOME_UNKNOWN", timeout.persistedCode)
    }
}
