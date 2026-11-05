package com.maodouchat.core.model

data class ServerInfo(
    val name: String,
    val description: String,
    val announcement: String,
    val contactUrl: String,
    val version: String,
    val registrationOpen: Boolean,
    val baseUrl: String
)
