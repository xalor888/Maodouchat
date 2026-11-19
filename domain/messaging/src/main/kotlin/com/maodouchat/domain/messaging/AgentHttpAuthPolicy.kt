package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.LocalAiProtocol
import com.maodouchat.domain.messaging.LocalAiProtocolCodec
import com.maodouchat.domain.messaging.LocalAiProvider

// 模型接口鉴权头装配：只算头列表，不碰网络，自 app 侧
// OpenAiCompatHttp.applyAuth 收敛，调用方按序 addHeader。
object AgentHttpAuthPolicy {

    const val DEFAULT_ANTHROPIC_VERSION: String = "2023-06-01"

    fun authHeaders(provider: LocalAiProvider): List<Pair<String, String>> =
        buildList {
            when (provider.protocol) {
                LocalAiProtocol.ANTHROPIC_MESSAGES -> {
                    add("x-api-key" to provider.apiKey)
                    add(
                        "anthropic-version" to
                            provider.anthropicVersion.ifBlank { DEFAULT_ANTHROPIC_VERSION }
                    )
                    if (provider.apiKey.isNotBlank()) {
                        add("Authorization" to "Bearer ${provider.apiKey}")
                    }
                }
                else -> {
                    add("Authorization" to "Bearer ${provider.apiKey}")
                    if (provider.organization.isNotBlank()) {
                        add("OpenAI-Organization" to provider.organization)
                    }
                }
            }
            LocalAiProtocolCodec.extraHeaders(provider).forEach { (k, v) -> add(k to v) }
        }
}
