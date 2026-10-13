package com.maodouchat.server.repository

// 在线状态簇（广播/可见性/写在线）：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * 检查用户是否允许广播在线状态（showOnline 隐私设置）
 */
fun UserRepository.shouldBroadcastOnline(userId: String): Boolean {
    return transaction {
        Users.selectAll().where { Users.id eq userId }
            .firstOrNull()?.let { row ->
                UserRepository.onlineVisibleToAnyone(row[Users.showOnline], row[Users.onlineVisibility])
            } ?: false
    }
}


fun UserRepository.shouldShowOnlineTo(targetId: String, viewerId: String?): Boolean {
    if (viewerId.isNullOrBlank()) return false
    if (targetId == viewerId) return true
    return transaction {
        val row = Users.selectAll().where { Users.id eq targetId }.firstOrNull() ?: return@transaction false
        UserRepository.onlineVisibleToViewer(
            showOnline = row[Users.showOnline],
            visibility = row[Users.onlineVisibility],
            targetId = targetId,
            viewerId = viewerId
        )
    }
}


fun UserRepository.setOnline(userId: String, online: Boolean) {
    transaction {
        Users.update({ Users.id eq userId }) {
            it[isOnline] = online
            it[lastSeen] = System.currentTimeMillis()
        }
    }
}
