package com.maodouchat.domain.messaging

data class Parameter(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null
)
