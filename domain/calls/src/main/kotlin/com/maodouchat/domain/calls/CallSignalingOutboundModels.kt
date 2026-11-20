package com.maodouchat.domain.calls

data class Ticket(
    val epoch: Long,
    val sequence: Long,
    val idempotencyKey: String,
)
