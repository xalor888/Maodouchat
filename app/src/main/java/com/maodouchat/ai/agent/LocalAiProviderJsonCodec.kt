package com.maodouchat.ai.agent

import com.maodouchat.ai.agent.LocalAiStorePrefs.optDoubleOrNull
import org.json.JSONArray
import org.json.JSONObject

// provider JSON 编解码簇：LocalAiProvider 列表与 prefs 字符串互转。
// 原 LocalAiProviderStorage 的编解码部分整体搬入；经门面 LocalAiProviderStore 供测试引用。
internal object LocalAiProviderJsonCodec {
    internal fun parseProviders(raw: String): List<LocalAiProvider> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val model = o.optString("model").trim()
                val base = o.optString("baseUrl").trim().trimEnd('/')
                if (id.isBlank() || model.isBlank() || base.isBlank()) continue
                add(
                    LocalAiProvider(
                        id = id,
                        name = o.optString("name").ifBlank { model },
                        baseUrl = base,
                        apiKey = o.optString("apiKey"),
                        model = model,
                        protocol = LocalAiProtocolCodec.parseProtocol(o.optString("protocol")),
                        anthropicVersion = o.optString("anthropicVersion").ifBlank { "2023-06-01" },
                        organization = o.optString("organization"),
                        extraHeadersJson = o.optString("extraHeadersJson").ifBlank { "{}" },
                        temperature = o.optDoubleOrNull("temperature"),
                        topP = o.optDoubleOrNull("topP"),
                        maxTokens = o.optInt("maxTokens", 4_096),
                        contextWindowTokens = o.optInt("contextWindowTokens", 128_000),
                        historyMessageLimit = o.optInt("historyMessageLimit", 24),
                        timeoutSeconds = o.optInt("timeoutSeconds", 120),
                        stream = o.optBoolean("stream", true),
                        supportsVision = o.optBoolean("supportsVision", false)
                    )
                )
            }
        }
    }

    internal fun encodeProviders(list: List<LocalAiProvider>): String {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("apiKey", p.apiKey)
                    .put("model", p.model)
                    .put("protocol", p.protocol.name)
                    .put("anthropicVersion", p.anthropicVersion)
                    .put("organization", p.organization)
                    .put("extraHeadersJson", p.extraHeadersJson)
                    .put("temperature", p.temperature ?: JSONObject.NULL)
                    .put("topP", p.topP ?: JSONObject.NULL)
                    .put("maxTokens", p.maxTokens)
                    .put("contextWindowTokens", p.contextWindowTokens)
                    .put("historyMessageLimit", p.historyMessageLimit)
                    .put("timeoutSeconds", p.timeoutSeconds)
                    .put("stream", p.stream)
                    .put("supportsVision", p.supportsVision)
            )
        }
        return array.toString()
    }
}
