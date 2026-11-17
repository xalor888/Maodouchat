package com.maodouchat.domain.messaging

// 自配模型端点可用性判定，自 app 侧 provider 草稿簇收敛；
// 端点读取仍在 app 侧，这里只定三字段非空规则。
object AgentProviderConfiguredPolicy {

    fun isConfigured(baseUrl: String, model: String, apiKey: String): Boolean =
        baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}
