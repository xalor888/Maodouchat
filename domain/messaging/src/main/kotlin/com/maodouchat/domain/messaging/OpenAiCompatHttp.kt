package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentHttpAuthPolicy
import com.maodouchat.domain.messaging.LocalAiProvider
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

// 三簇客户端（对话 / 视觉 / 音频）共用的 HTTP 装配：超时与鉴权头都从 provider 取。
internal object OpenAiCompatHttp {
    internal val jsonMedia = "application/json; charset=utf-8".toMediaType()

    internal fun clientFor(provider: LocalAiProvider, baseClient: OkHttpClient): OkHttpClient {
        val timeout = provider.clampedTimeoutSeconds().toLong()
        return baseClient.newBuilder().readTimeout(timeout, TimeUnit.SECONDS).build()
    }

    internal fun applyAuth(builder: Request.Builder, provider: LocalAiProvider) {
        AgentHttpAuthPolicy.authHeaders(provider).forEach { (k, v) -> builder.addHeader(k, v) }
    }
}
