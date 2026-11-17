package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentToolArgs
import com.maodouchat.domain.messaging.AgentToolHostPolicy
import com.maodouchat.network.TokenManager

object AgentToolHost {
    suspend fun execute(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        val app = MaodouchatApp.instance
        val userId = TokenManager.getInstance(app).getUserId().orEmpty()
        if (userId.isBlank()) return AgentToolHostPolicy.NOT_SIGNED_IN
        if (name == "rewrite_text") return AgentToolHostPolicy.REWRITE_HANDLED_BY_ENGINE
        return (AgentMessagingToolHost.execute(name, app, userId, args)
            ?: AgentSocialToolHost.execute(name, app, userId, args)
            ?: AgentTaskToolHost.execute(name, app, userId, args)
            ?: AgentToolHostPolicy.unknownTool(name)).let { AgentToolHostPolicy.truncateResult(it) }
    }

    fun preview(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        return AgentMessagingToolHost.preview(name, args)
            ?: AgentSocialToolHost.preview(name, args)
            ?: AgentTaskToolHost.preview(name, args)
            ?: AgentToolHostPolicy.previewFallback(name, argumentsJson)
    }

    internal fun parseArgs(raw: String): Map<String, String> = AgentToolArgs.parseArgs(raw)

    internal fun parseBool(raw: String?): Boolean? = AgentToolArgs.parseBool(raw)

    internal fun token(app: MaodouchatApp): String? =
        TokenManager.getInstance(app).getToken()?.takeIf { it.isNotBlank() }

    internal fun fail(error: Throwable): String = AgentToolArgs.fail(error)
}
