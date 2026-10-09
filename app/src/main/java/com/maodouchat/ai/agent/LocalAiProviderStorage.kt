package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.ai.agent.LocalAiStorePrefs.optDoubleOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// provider 簇：用户自配模型端点的增删改查、草稿、可用性判定与 JSON 编解码。原 LocalAiProviderStore 的 provider 部分整体搬入。
internal object LocalAiProviderStorage {
    fun listProviders(context: Context): List<LocalAiProvider> {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return emptyList()
        val raw = prefs.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), "[]").orEmpty()
        return parseProviders(raw)
    }

    fun activeProvider(context: Context): LocalAiProvider? {
        val list = listProviders(context)
        if (list.isEmpty()) return null
        val activeId = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun upsertProvider(context: Context, provider: LocalAiProvider): LocalAiProvider {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return provider
        val next = listProviders(context).filterNot { it.id == provider.id } + provider
        prefs.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), encodeProviders(next))
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), provider.id)
        }
        return provider
    }

    fun deleteProvider(context: Context, id: String) {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return
        val next = listProviders(context).filterNot { it.id == id }
        prefs.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), encodeProviders(next))
            val active = prefs.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), null)
            if (active == id) {
                putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), next.firstOrNull()?.id.orEmpty())
            }
        }
    }

    fun setActive(context: Context, id: String) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), id)
        }
    }

    fun newProviderDraft(protocol: LocalAiProtocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS): LocalAiProvider {
        val (name, base, model) = when (protocol) {
            LocalAiProtocol.OPENAI_CHAT_COMPLETIONS ->
                Triple("OpenAI Chat Completions", "https://api.openai.com/v1", "gpt-4o-mini")
            LocalAiProtocol.OPENAI_RESPONSES ->
                Triple("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini")
            LocalAiProtocol.ANTHROPIC_MESSAGES ->
                Triple("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5")
        }
        return LocalAiProvider(
            id = "p_${UUID.randomUUID()}",
            name = name,
            baseUrl = base,
            apiKey = "",
            model = model,
            protocol = protocol
        )
    }

    fun isConfigured(context: Context): Boolean {
        val p = activeProvider(context) ?: return false
        return p.baseUrl.isNotBlank() && p.model.isNotBlank() && p.apiKey.isNotBlank()
    }

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
