package com.maodouchat.server.repository

import kotlinx.serialization.Serializable

/**
 * 群投票子域（B3 / B05 独立子域）。
 *
 * 投票 CRUD 与投票列表同步快照、群成员名单（WS fanout）统一收敛于此。
 * 票选记录为群内公开元数据，明文存储。
 */
object PollRepository {

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

}
