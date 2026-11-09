package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.AttachmentTransferDisposition
import java.io.IOException

/** 附件上传错误处置（纯逻辑）：失败码、可重试判定与 claim 失败处置。 */
object AttachmentTransferErrorPolicy {

    /** 本地源文件缺失（加密路径不在私有上传目录、非文件或大小不符）。 */
    const val ERROR_SOURCE_MISSING = "SOURCE_MISSING"

    /** 传输行处于 worker 无法继续的状态。 */
    const val ERROR_INVALID_STATE = "INVALID_STATE"

    fun transferErrorCode(error: Throwable): String = when (error) {
        is ApiException -> "${error.kind.name}_${error.statusCode ?: 0}"
        else -> error.javaClass.simpleName.take(80).ifBlank { "UNKNOWN" }
    }

    fun isRetryableTransferError(error: Throwable): Boolean = when (error) {
        is ApiException -> error.retryAfterSeconds != null ||
            error.kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT) ||
            error.statusCode == 429 ||
            (error.statusCode ?: 0) >= 500
        else -> error is IOException
    }

    // claim 失败 ≠ 任务结束：仍在待上传态（QUEUED/UPLOADING/FAILED）时重试，等 stale 窗口过期后重领。
    fun claimFailureDisposition(state: String?): AttachmentTransferDisposition = when (state) {
        "QUEUED", "UPLOADING", "FAILED" -> AttachmentTransferDisposition.RETRY
        else -> AttachmentTransferDisposition.SUCCESS
    }
}
