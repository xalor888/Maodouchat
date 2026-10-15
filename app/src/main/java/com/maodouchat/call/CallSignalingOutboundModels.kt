package com.maodouchat.call

data class Ticket(
    val epoch: Long,
    val sequence: Long,
    val idempotencyKey: String,
)
