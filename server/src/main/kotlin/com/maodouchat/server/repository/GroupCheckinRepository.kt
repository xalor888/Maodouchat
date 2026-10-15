package com.maodouchat.server.repository

import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

// 群签到 / 群接龙 / 群 PK：群内公开元数据，明文存储；写操作校验群聊 + 成员身份。
object GroupCheckinRepository {

    // 清理超过保留期的群玩法数据（默认 365 天），返回表名 -> 删除行数。
    fun purgeOldData(retentionDays: Int = 365): Map<String, Int> {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            val staleChainIds = GroupChains.select(GroupChains.id)
                .where { GroupChains.createdAt less cutoff }
                .map { it[GroupChains.id] }
            val entryDeletedByChain = if (staleChainIds.isNotEmpty()) {
                GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList staleChainIds }
            } else 0
            val stalePkIds = GroupPkRounds.select(GroupPkRounds.id)
                .where { GroupPkRounds.createdAt less cutoff }
                .map { it[GroupPkRounds.id] }
            val voteDeletedByPk = if (stalePkIds.isNotEmpty()) {
                GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList stalePkIds }
            } else 0
            mapOf(
                "checkins" to GroupCheckins.deleteWhere { GroupCheckins.checkedAt less cutoff },
                "chains" to GroupChains.deleteWhere { GroupChains.createdAt less cutoff },
                "chainEntries" to (entryDeletedByChain + GroupChainEntries.deleteWhere { GroupChainEntries.createdAt less cutoff }),
                "pkRounds" to GroupPkRounds.deleteWhere { GroupPkRounds.createdAt less cutoff },
                "pkVotes" to (voteDeletedByPk + GroupPkVotes.deleteWhere { GroupPkVotes.votedAt less cutoff })
            )
        }
    }

}
