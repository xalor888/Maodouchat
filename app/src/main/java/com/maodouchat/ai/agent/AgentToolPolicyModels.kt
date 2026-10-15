package com.maodouchat.ai.agent

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
