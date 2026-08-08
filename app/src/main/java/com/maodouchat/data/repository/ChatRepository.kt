package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull

/**
 * 会话数据仓库
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val userDao: UserDao
) {

    /** 获取活跃会话（未归档，置顶优先，按最后消息时间排序） */
    fun getActiveChats(): Flow<List<Chat>> =
        chatDao.getActiveChats().combine(userDao.getAllUsers()) { chats, users ->
            val userMap = users.associate { it.id to it.toDomain() }
            chats.map { it.toDomain(userMap) }
        }

    /** 获取归档会话 */
    fun getArchivedChats(): Flow<List<Chat>> =
        chatDao.getArchivedChats().combine(userDao.getAllUsers()) { chats, users ->
            val userMap = users.associate { it.id to it.toDomain() }
            chats.map { it.toDomain(userMap) }
        }

    /** 获取所有会话（含归档，兼容旧调用） */
    fun getAllChats(): Flow<List<Chat>> =
        chatDao.getAllChats().combine(userDao.getAllUsers()) { chats, users ->
            val userMap = users.associate { it.id to it.toDomain() }
            chats.map { it.toDomain(userMap) }
        }

    /** 从本地缓存获取单个会话（离线回退） */
    suspend fun getChatById(chatId: String): Chat? {
        val chatEntity = chatDao.getChatById(chatId) ?: return null
        val users = userDao.getAllUsers().firstOrNull().orEmpty()
        val userMap = users.associate { it.id to it.toDomain() }
        return chatEntity.toDomain(userMap)
    }

    /** 批量缓存服务端会话和参与者，保障离线列表可用 */
    suspend fun cacheChats(chats: List<Chat>) {
        if (chats.isEmpty()) return
        val users = chats.flatMap { it.participants }.distinctBy { it.id }
        if (users.isNotEmpty()) {
            // 服务端 UserDto 无本地备注：空 nickname 时保留库中已有备注；
            // 同时保留本地已有的 avatar/email/在线态/状态（聊天列表参与者通常不含这些字段）。
            // 8.31 性能修复 F14：批量读取已有用户（一次 SQL 替代逐用户 N+1）。
            val existingById = if (users.size <= 1) {
                mapOf(users[0].id to userDao.getUserById(users[0].id))
            } else {
                userDao.getUsersByIds(users.map { it.id })
                    .associateBy { it.id }
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
                    // 8.38：同 UserRepository.insertUsers——lastSeen 仅入站真实值时更新
                    lastSeen = if (incoming.lastSeen > 0) incoming.lastSeen else existing?.lastSeen ?: 0
                ).toEntity()
            }
            userDao.insertUsers(merged)
        }
        chatDao.insertChats(chats.map { it.toEntity() })
    }

    /** 删除本地缓存的聊天（同时级联删除该聊天的消息） */
    suspend fun deleteChat(chatId: String) {
        chatDao.deleteChatById(chatId)
    }

    /** 置顶会话 */
    suspend fun pinChat(chatId: String) {
        chatDao.setPinned(chatId, System.currentTimeMillis())
    }

    /** 取消置顶 */
    suspend fun unpinChat(chatId: String) {
        chatDao.setUnpinned(chatId)
    }

    /** 归档会话 */
    suspend fun archiveChat(chatId: String) {
        chatDao.setArchived(chatId, archived = true)
    }

    /** 取消归档 */
    suspend fun unarchiveChat(chatId: String) {
        chatDao.setArchived(chatId, archived = false)
    }

    /** 免打扰 */
    suspend fun muteChat(chatId: String) {
        chatDao.setMuted(chatId, muted = true)
    }

    /** 取消免打扰 */
    suspend fun unmuteChat(chatId: String) {
        chatDao.setMuted(chatId, muted = false)
    }

    /** 标记未读 */
    suspend fun markChatUnread(chatId: String) {
        chatDao.setMarkedUnread(chatId, marked = true)
    }

    /** 取消标记未读 */
    suspend fun markChatRead(chatId: String) {
        chatDao.setMarkedUnread(chatId, marked = false)
    }
}
