package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiClassifiedError
import com.maodouchat.core.model.AiCostVisibilityPolicy
import com.maodouchat.core.model.AiRetryPolicyCategory
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.PendingAiAction

// AI 操作调用分级：图片/文件分析与群助手是重型，其余是轻型。
fun PendingAiAction.invocationCategory(): AiRetryPolicyCategory = when (this) {
    is PendingAiAction.AnalyzeImage,
    is PendingAiAction.AnalyzeFile,
    is PendingAiAction.GroupAssistant -> AiRetryPolicyCategory.HEAVY
    else -> AiRetryPolicyCategory.LIGHT
}

// Throwable 归类为错误码字符串；字面量与 app 侧 AiOperationError 常量逐字同值。
fun classifyAiOperationError(error: Throwable): String = when {
    error is ApiException &&
        error.kind == ApiFailureKind.NETWORK &&
        !error.requestMayHaveReachedServer -> "CONNECTION_NOT_ESTABLISHED"
    error is ApiException && error.kind in setOf(
        ApiFailureKind.NETWORK,
        ApiFailureKind.TIMEOUT,
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNEXPECTED
    ) -> "OUTCOME_UNKNOWN"
    error is ApiException && error.kind == ApiFailureKind.HTTP -> {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = error.statusCode,
            serverCode = error.serverCode,
            serverMessage = error.serverMessage,
            serverRetryAfterSeconds = error.retryAfterSeconds
        )
        AiCostVisibilityPolicy.mapToErrorCode(signal)
            ?: "SERVER"
    }
    else -> "UNKNOWN"
}

fun classifyAiOperationErrorDetailed(error: Throwable): AiClassifiedError {
    val code = classifyAiOperationError(error)
    val retry = (error as? ApiException)?.retryAfterSeconds?.takeIf { it > 0L }
        ?: if (code == "RATE_LIMITED" || code == "QUOTA_EXCEEDED") {
            AiCostVisibilityPolicy.waitSecondsFor(code).takeIf { it > 0L }
        } else null
    return AiClassifiedError(
        code = code,
        retryAfterSeconds = retry,
        persistedCode = AiCostVisibilityPolicy.encodeErrorCode(code, retry)
    )
}
