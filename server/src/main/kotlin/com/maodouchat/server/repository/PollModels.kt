package com.maodouchat.server.repository

import kotlinx.serialization.Serializable

@Serializable
data class PollDto(
    val id: String,
    val chatId: String,
    val creatorId: String,
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
    val closed: Boolean,
    val createdAt: Long,
    val closesAt: Long? = null,
    val counts: List<Int> = emptyList(),
    val myVotes: List<Int> = emptyList(),
    val totalVoters: Int = 0
)

/** 投票公开快照（广播给群成员时使用的轻量版，字段与 [PollDto] 兼容）。 */
@Serializable
data class PollSnapshot(
    val id: String,
    val chatId: String,
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
    val closed: Boolean,
    val createdAt: Long,
    val closesAt: Long? = null,
    val counts: List<Int> = emptyList(),
    val totalVoters: Int = 0,
    val creatorId: String = "",
    val myVotes: List<Int> = emptyList()
)
