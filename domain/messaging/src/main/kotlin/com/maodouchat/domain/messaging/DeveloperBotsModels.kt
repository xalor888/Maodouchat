package com.maodouchat.domain.messaging

data class BotUi(
    val id: String,
    val name: String,
    val username: String,
    val tokenPrefix: String,
    val webhookUrl: String,
    val enabled: Boolean,
    val tokenOnce: String = ""
)
