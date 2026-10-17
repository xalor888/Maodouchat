package com.maodouchat.ai.agent

data class Parameter(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null
)
