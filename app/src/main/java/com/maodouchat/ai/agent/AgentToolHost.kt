package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import org.json.JSONObject

object AgentToolHost {
    suspend fun execute(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        val app = MaodouchatApp.instance
        val userId = TokenManager.getInstance(app).getUserId().orEmpty()
        if (userId.isBlank()) return "Error: not signed in"
        if (name == "rewrite_text") return "Error: rewrite_text is handled by the engine, not the host"
        return (AgentMessagingToolHost.execute(name, app, userId, args)
            ?: AgentSocialToolHost.execute(name, app, userId, args)
            ?: AgentTaskToolHost.execute(name, app, userId, args)
            ?: "Error: unknown tool $name").take(AgentToolPolicy.MAX_TOOL_RESULT_CHARS)
    }

    fun preview(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        return AgentMessagingToolHost.preview(name, args)
            ?: AgentSocialToolHost.preview(name, args)
            ?: AgentTaskToolHost.preview(name, args)
            ?: "$name ${argumentsJson.take(160)}"
    }

    internal fun parseArgs(raw: String): Map<String, String> {
        val obj = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key) ?: return@forEach
            out[key] = value.toString()
        }
        return out
    }

    internal fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    internal fun token(app: MaodouchatApp): String? =
        TokenManager.getInstance(app).getToken()?.takeIf { it.isNotBlank() }

    internal fun fail(error: Throwable): String = "Error: ${error.message ?: error.javaClass.simpleName}"
}
