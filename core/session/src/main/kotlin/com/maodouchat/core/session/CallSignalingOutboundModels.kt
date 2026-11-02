package com.maodouchat.core.session

data class Ticket(
    val epoch: Long,
    val sequence: Long,
    val idempotencyKey: String,
)
