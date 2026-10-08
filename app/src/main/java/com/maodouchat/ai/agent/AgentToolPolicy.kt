package com.maodouchat.ai.agent

/**
 * Pure rules for the on-device Maodou assistant.
 *
 * Chat ciphertext never goes to the Maodou server. The model is a user-configured
 * OpenAI-compatible endpoint. Tools read already-decrypted SQLCipher rows and send
 * only through the existing E2EE outbox.
 */
object AgentToolPolicy {
    const val MAX_TOOL_ROUNDS = 12
    const val MAX_HISTORY_MESSAGES = 24
    const val MAX_CHAT_HISTORY = 40
    const val MAX_SEARCH_HITS = 20
    const val MAX_TOOL_RESULT_CHARS = 6_000
    const val MAX_TEXT_SEND_CHARS = 4_000
    const val MAX_LIST_CHATS = 80
    const val MAX_DRAFT_CHARS = 4_000
    const val MAX_NICKNAME_CHARS = 40

    enum class Risk {
        READ,
        WRITE,
        SEND
    }

    enum class Approval {
        ALLOW,
        NEED_USER,
        DENY
    }

    data class ToolSpec(
        val name: String,
        val description: String,
        val parameters: Map<String, Parameter>,
        val required: List<String>,
        val risk: Risk
    ) {
        data class Parameter(
            val type: String,
            val description: String,
            val enumValues: List<String>? = null
        )
    }

    val tools: List<ToolSpec> = agentToolCatalog

    fun toolByName(name: String): ToolSpec? = tools.firstOrNull { it.name == name }

    fun approvalFor(name: String, arguments: Map<String, String>): Approval {
        val spec = toolByName(name) ?: return Approval.DENY
        return when (spec.risk) {
            Risk.READ -> Approval.ALLOW
            Risk.WRITE, Risk.SEND -> Approval.NEED_USER
        }
    }

    fun openaiToolsJson(): List<Map<String, Any?>> = tools.map { spec ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to spec.name,
                "description" to spec.description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to spec.parameters.mapValues { (_, p) ->
                        buildMap<String, Any?> {
                            put("type", p.type)
                            put("description", p.description)
                            if (p.enumValues != null) put("enum", p.enumValues)
                        }
                    },
                    "required" to spec.required
                )
            )
        )
    }

}
