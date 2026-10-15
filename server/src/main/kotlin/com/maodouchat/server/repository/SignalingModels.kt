package com.maodouchat.server.repository

data class SignalingMessage(
    val id: String,
    val fromUserId: String,
    val toUserId: String,
    val callId: String,
    val groupId: String,
    val groupMemberIds: List<String>,
    val groupInvite: Boolean,
    val type: String,
    val payload: String,
    val epoch: Long,
    val sequence: Long,
    val idempotencyKey: String,
    val timestamp: Long
)
