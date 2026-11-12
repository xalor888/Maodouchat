package com.maodouchat.domain.calls

import com.maodouchat.core.model.SignalingException

// 通话错误原因文案种类（app 侧映射到 R.string）。
enum class CallErrorMessage {
    RATE_LIMITED,
    NETWORK_ERROR,
}

// failureReason 的纯决策结果：资源文案由 app 侧映射，非信令异常的 message 原样透传。
sealed interface CallErrorText {
    data class Res(val message: CallErrorMessage, val args: List<Any> = emptyList()) : CallErrorText
    data class Literal(val text: String) : CallErrorText
}

object CallErrorReason {
    fun resolve(error: Throwable): CallErrorText =
        if (error is SignalingException && error.code == "CALL_INVITE_RATE_LIMITED") {
            CallErrorText.Res(CallErrorMessage.RATE_LIMITED, listOf(error.retryAfterSeconds ?: 60))
        } else if (error is SignalingException) {
            CallErrorText.Res(CallErrorMessage.NETWORK_ERROR)
        } else if (error.message != null) {
            CallErrorText.Literal(error.message!!)
        } else {
            CallErrorText.Res(CallErrorMessage.NETWORK_ERROR)
        }
}
