package com.maodouchat.server.repository

import kotlinx.serialization.Serializable

/** 群接龙子域（B05：签到/接龙/PK 分独立子域，自 GroupCheckinRepository 拆出）。 */
class GroupChainRepository

@Serializable
data class ChainEntryDto(
    val id: String,
    val userId: String,
    val sequence: Int,
    val content: String,
    val createdAt: Long
)

@Serializable
data class ChainDto(
    val id: String,
    val chatId: String,
    val creatorId: String,
    val title: String,
    val topic: String,
    val maxEntries: Int,
    val active: Boolean,
    val createdAt: Long,
    val closedAt: Long? = null,
    val entryCount: Int = 0,
    val myJoined: Boolean = false,
    val entries: List<ChainEntryDto> = emptyList()
)

internal const val MIN_CHAIN_ENTRIES = 2
internal const val MAX_CHAIN_ENTRIES = 1_000
internal const val MAX_CHAIN_TITLE_LENGTH = 200
internal const val MAX_CHAIN_TOPIC_LENGTH = 500
internal const val MAX_CHAIN_CONTENT_LENGTH = 500
