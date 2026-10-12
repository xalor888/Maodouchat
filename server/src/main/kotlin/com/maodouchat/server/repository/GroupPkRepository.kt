package com.maodouchat.server.repository

import kotlinx.serialization.Serializable

/** 群 PK 对决子域（B05：签到/接龙/PK 分独立子域，自 GroupCheckinRepository 拆出）。 */
class GroupPkRepository {

    @Serializable
    data class PkDto(
        val id: String,
        val chatId: String,
        val creatorId: String,
        val leftTitle: String,
        val rightTitle: String,
        val active: Boolean,
        val createdAt: Long,
        val closedAt: Long? = null,
        val leftCount: Int = 0,
        val rightCount: Int = 0,
        val totalVoters: Int = 0,
        val myChoice: String? = null
    )

    companion object {
        internal const val MAX_PK_TITLE_LENGTH = 120
    }
}
