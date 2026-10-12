package com.maodouchat.server.service

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

internal fun AccountLifecycleService.deletePollsCreatedBy(creatorIds: List<String>) {
    if (creatorIds.isEmpty()) return
    val pollIds = GroupPolls.select(GroupPolls.id)
        .where { GroupPolls.creatorId inList creatorIds }
        .orderBy(GroupPolls.id to SortOrder.ASC)
        .forUpdate()
        .map { it[GroupPolls.id] }
    if (pollIds.isEmpty()) return
    GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
    GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
}

internal fun AccountLifecycleService.deleteGroupPlayCreatedBy(creatorIds: List<String>) {
    if (creatorIds.isEmpty()) return
    val chainIds = GroupChains.select(GroupChains.id)
        .where { GroupChains.creatorId inList creatorIds }
        .map { it[GroupChains.id] }
    if (chainIds.isNotEmpty()) {
        GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
        GroupChains.deleteWhere { GroupChains.id inList chainIds }
    }
    val pkIds = GroupPkRounds.select(GroupPkRounds.id)
        .where { GroupPkRounds.creatorId inList creatorIds }
        .map { it[GroupPkRounds.id] }
    if (pkIds.isNotEmpty()) {
        GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
        GroupPkRounds.deleteWhere { GroupPkRounds.id inList pkIds }
    }
}
