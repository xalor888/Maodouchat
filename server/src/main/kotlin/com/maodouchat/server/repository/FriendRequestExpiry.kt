package com.maodouchat.server.repository

// 好友请求过期清理簇：超期 PENDING 清理（原 FriendRequestLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.FriendRequests
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 清理超过期限仍未处理的 PENDING 好友请求（默认 30 天），防止无限堆积。
 * 由 Routing.kt 的周期清理循环调用；超期未处理的请求对双方都无意义。
 */
fun FriendRepository.expireStalePending(days: Int = 30): Int {
    val cutoff = System.currentTimeMillis() - days * 86_400_000L
    return transaction {
        FriendRequests.deleteWhere {
            (FriendRequests.status eq "PENDING") and (FriendRequests.createdAt less cutoff)
        }
    }
}
