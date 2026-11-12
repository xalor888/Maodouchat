package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.R
import com.maodouchat.domain.calls.CallErrorMessage
import com.maodouchat.domain.calls.CallErrorReason
import com.maodouchat.domain.calls.CallErrorText

// 文案簇：从 CallViewModel 纯搬移——text()/failureReason() 原样搬入，
// 各 controller 的接线 lambda 不变，VM 只留同签名委托。
// failureReason 的按码分流决策在 :domain:calls（CallErrorReason），这里只做资源映射。
internal class CallErrorMessages(application: Application) {
    private val app = application

    fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    fun failureReason(error: Throwable): String =
        when (val resolved = CallErrorReason.resolve(error)) {
            is CallErrorText.Literal -> resolved.text
            is CallErrorText.Res -> text(callErrorMessageResId(resolved.message), *resolved.args.toTypedArray())
        }
}

internal fun callErrorMessageResId(message: CallErrorMessage): Int = when (message) {
    CallErrorMessage.RATE_LIMITED -> R.string.call_rate_limited
    CallErrorMessage.NETWORK_ERROR -> R.string.call_network_error
}
