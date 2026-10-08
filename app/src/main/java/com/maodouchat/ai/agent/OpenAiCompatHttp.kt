package com.maodouchat.ai.agent

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
        when (provider.protocol) {
            LocalAiProtocol.ANTHROPIC_MESSAGES -> {
                builder.addHeader("x-api-key", provider.apiKey)
                builder.addHeader("anthropic-version", provider.anthropicVersion.ifBlank { "2023-06-01" })
                if (provider.apiKey.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer ${provider.apiKey}")
                }
            }
            else -> {
                builder.addHeader("Authorization", "Bearer ${provider.apiKey}")
                if (provider.organization.isNotBlank()) {
                    builder.addHeader("OpenAI-Organization", provider.organization)
                }
            }
        }
        LocalAiProtocolCodec.extraHeaders(provider).forEach { (k, v) -> builder.addHeader(k, v) }
    }
}
