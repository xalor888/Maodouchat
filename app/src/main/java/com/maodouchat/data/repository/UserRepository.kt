package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户数据仓库
 */
class UserRepository(private val userDao: UserDao) {

    /** 获取所有用户（按名称排序） */
    fun getAllUsers(): Flow<List<User>> =
        userDao.getAllUsers().map { list -> list.map { it.toDomain() } }

    /** 按 ID 获取单个用户（离线回退） */
    suspend fun getUserById(userId: String): User? =
        userDao.getUserById(userId)?.toDomain()

    /** 本地关键字搜索联系人（用于离线回退） */
    suspend fun searchUsers(keyword: String, limit: Int = 50): List<User> {
        val escaped = com.maodouchat.data.local.LikeQueryPolicy.escapeForContains(keyword)
        if (escaped.isBlank()) return emptyList()
        return userDao.searchUsers(escaped, limit).map { it.toDomain() }
    }

    /**
     * 批量插入/更新用户资料。
     * 服务端 UserDto 不含本地备注：若传入 nickname 为空，则保留库中已有备注。
     */
    suspend fun insertUsers(users: List<User>) {
        if (users.isEmpty()) return
        // 合并时保留本地已有的 avatar/email/在线态/状态：聊天列表同步下发的参与者
        // UserDto 通常不含这些字段（null/空），若直接覆盖会把已缓存的头像、实时在线态清掉。
        // 仅当入站确实携带非空值时才更新（全量资料刷新路径不受影响）。
        // 9.213：批量预查替代逐条 getUserById（N+1），分批 500 规避绑定变量上限。
        val existingById = HashMap<String, com.maodouchat.data.local.entity.UserEntity>(users.size)
        users.map { it.id }.chunked(500).forEach { chunk ->
            userDao.getUsersByIds(chunk).forEach { existingById[it.id] = it }
        }
        val merged = users.map { incoming ->
            val existing = existingById[incoming.id]
            val nick = incoming.nickname?.takeIf { it.isNotBlank() } ?: existing?.nickname
            incoming.copy(
                nickname = nick,
                avatar = incoming.avatar?.takeIf { it.isNotBlank() } ?: existing?.avatar,
                email = incoming.email.takeIf { it.isNotBlank() } ?: existing?.email ?: "",
                isOnline = if (incoming.isOnline) true else existing?.isOnline ?: false,
                status = incoming.status.takeIf { it.isNotBlank() } ?: existing?.status ?: "",
                // 8.38：lastSeen 仅当入站携带真实值才更新，否则保留本地（避免聊天列表下发
                // 的参与者 UserDto 无 lastSeen 时把已有最近上线时间清成 0）
                lastSeen = if (incoming.lastSeen > 0) incoming.lastSeen else existing?.lastSeen ?: 0
            ).toEntity()
        }
        userDao.insertUsers(merged)
    }

    /** 给联系人设置本地备注名 */
    suspend fun setNickname(userId: String, nickname: String) =
        userDao.setNickname(userId, nickname.ifBlank { null })
}
