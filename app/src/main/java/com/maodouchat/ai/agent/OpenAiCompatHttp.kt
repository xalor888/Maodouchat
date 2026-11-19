package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentHttpAuthPolicy
import com.maodouchat.domain.messaging.LocalAiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

// 三簇客户端（对话 / 视觉 / 音频）共用的 HTTP 装配：超时与鉴权头都从 provider 取。
internal object OpenAiCompatHttp {
    internal val jsonMedia = "application/json; charset=utf-8".toMediaType()

    internal fun clientFor(provider: LocalAiProvider): OkHttpClient {
        val timeout = provider.clampedTimeoutSeconds().toLong()
        return com.maodouchat.network.HttpClients.chatModel(timeout)
    }

    internal fun applyAuth(builder: Request.Builder, provider: LocalAiProvider) {
        AgentHttpAuthPolicy.authHeaders(provider).forEach { (k, v) -> builder.addHeader(k, v) }
    }
}
