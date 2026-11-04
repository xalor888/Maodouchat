package com.maodouchat.core.model

data class Parameter(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null
)
