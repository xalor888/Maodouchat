package com.maodouchat.ai.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.maodouchat.network.TokenManager
import com.maodouchat.security.AccountIsolationPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Account-scoped encrypted store for user-owned model endpoints.
 * Keys never go to the Maodou chat server.
 */
object LocalAiProviderStore {
    private const val PREFS = "maodou_local_ai"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_ACTIVE = "active_provider_id"
    private const val KEY_OVERLAY = "overlay_mode"
    private const val KEY_SESSIONS = "sessions_json"

    fun listProviders(context: Context): List<LocalAiProvider> {
        val prefs = prefs(context) ?: return emptyList()
        val raw = prefs.getString(scoped(context, KEY_PROVIDERS), "[]").orEmpty()
        return parseProviders(raw)
    }

    fun activeProvider(context: Context): LocalAiProvider? {
        val list = listProviders(context)
        if (list.isEmpty()) return null
        val activeId = prefs(context)?.getString(scoped(context, KEY_ACTIVE), null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun upsertProvider(context: Context, provider: LocalAiProvider): LocalAiProvider {
        val prefs = prefs(context) ?: return provider
        val next = listProviders(context).filterNot { it.id == provider.id } + provider
        prefs.edit()
            .putString(scoped(context, KEY_PROVIDERS), encodeProviders(next))
            .putString(scoped(context, KEY_ACTIVE), provider.id)
            .apply()
        return provider
    }

    fun deleteProvider(context: Context, id: String) {
        val prefs = prefs(context) ?: return
        val next = listProviders(context).filterNot { it.id == id }
        val editor = prefs.edit().putString(scoped(context, KEY_PROVIDERS), encodeProviders(next))
        val active = prefs.getString(scoped(context, KEY_ACTIVE), null)
        if (active == id) {
            editor.putString(scoped(context, KEY_ACTIVE), next.firstOrNull()?.id.orEmpty())
        }
        editor.apply()
    }

    fun setActive(context: Context, id: String) {
        prefs(context)?.edit()?.putString(scoped(context, KEY_ACTIVE), id)?.apply()
    }

    fun overlayMode(context: Context): AgentOverlayMode {
        val raw = prefs(context)?.getString(scoped(context, KEY_OVERLAY), AgentOverlayMode.DISABLED.name)
        return runCatching { AgentOverlayMode.valueOf(raw.orEmpty()) }.getOrDefault(AgentOverlayMode.DISABLED)
    }

    fun setOverlayMode(context: Context, mode: AgentOverlayMode) {
        prefs(context)?.edit()?.putString(scoped(context, KEY_OVERLAY), mode.name)?.apply()
    }

    fun loadSessions(context: Context): List<AgentSession> {
        val raw = prefs(context)?.getString(scoped(context, KEY_SESSIONS), "[]").orEmpty()
        return parseSessions(raw)
    }

    fun saveSessions(context: Context, sessions: List<AgentSession>) {
        prefs(context)?.edit()
            ?.putString(scoped(context, KEY_SESSIONS), encodeSessions(sessions.takeLast(12)))
            ?.apply()
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

    private fun scoped(context: Context, key: String): String {
        val userId = TokenManager.getInstance(context).getUserId().orEmpty()
        return AccountIsolationPolicy.preferenceKey(key, userId)
    }

    private fun prefs(context: Context): SharedPreferences? {
        val userId = TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() } ?: return null
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            "$PREFS-$userId",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
                        stream = o.optBoolean("stream", true)
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
            )
        }
        return array.toString()
    }

    internal fun parseSessions(raw: String): List<AgentSession> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                val msgs = o.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (j in 0 until msgs.length()) {
                        val m = msgs.optJSONObject(j) ?: continue
                        add(
                            AgentChatMessage(
                                role = m.optString("role"),
                                content = m.optString("content"),
                                toolCallId = m.optString("toolCallId").takeIf { it.isNotBlank() },
                                toolName = m.optString("toolName").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                add(
                    AgentSession(
                        id = id,
                        title = o.optString("title").ifBlank { "新对话" },
                        createdAt = o.optLong("createdAt"),
                        messages = messages
                    )
                )
            }
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { !it.isNaN() }
    }

    internal fun encodeSessions(list: List<AgentSession>): String {
        val array = JSONArray()
        list.forEach { s ->
            val msgs = JSONArray()
            s.messages.filter { it.role != "tool" || it.content.isNotBlank() }.forEach { m ->
                msgs.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("toolCallId", m.toolCallId.orEmpty())
                        .put("toolName", m.toolName.orEmpty())
                )
            }
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAt", s.createdAt)
                    .put("messages", msgs)
            )
        }
        return array.toString()
    }
}
