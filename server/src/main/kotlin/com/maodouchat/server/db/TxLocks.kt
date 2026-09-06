package com.maodouchat.server.db

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll

/**
 * 共享行锁原语（事务内调用）：按 id 排序加锁，固定锁序防死锁。
 * 收敛 ConversationCreationRepository / GroupMembershipRepository 各自的私有拷贝。
 */
internal fun lockUsersInTx(userIds: List<String>): List<ResultRow> {
    val orderedIds = userIds.distinct().sorted()
    if (orderedIds.isEmpty()) return emptyList()
    return Users.selectAll()
        .where { Users.id inList orderedIds }
        .orderBy(Users.id, SortOrder.ASC)
        .forUpdate()
        .toList()
}
