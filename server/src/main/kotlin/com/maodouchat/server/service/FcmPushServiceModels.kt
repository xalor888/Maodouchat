package com.maodouchat.server.service

internal data class Delivery(
    val recipientId: String,
    val isCall: Boolean,
    val data: Map<String, String>,
    val breakthroughDnd: Boolean = false
)
