package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.R
import com.maodouchat.webrtc.WebRTCSignaling

// 文案簇：从 CallViewModel 纯搬移——text()/failureReason() 原样搬入，
// 各 controller 的接线 lambda 不变，VM 只留同签名委托。
internal class CallErrorMessages(application: Application) {
    private val app = application

    fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    fun failureReason(error: Throwable): String =
        if (error is WebRTCSignaling.SignalingException && error.code == "CALL_INVITE_RATE_LIMITED") {
            text(R.string.call_rate_limited, error.retryAfterSeconds ?: 60)
        } else if (error is WebRTCSignaling.SignalingException) text(R.string.call_network_error)
        else error.message ?: text(R.string.call_network_error)
}
