package com.maodouchat.util

import java.util.UUID

/**
 * 会话文件夹（本地 + 云端同步元数据；chatIds 为会话 id，非消息内容）。
 * 纯函数，可单测。
 */
data class ChatFolder(
    val id: String,
    val name: String,
    val chatIds: List<String> = emptyList(),
    val sortOrder: Int = 0,
)

object ChatFolderPolicy {
    const val MAX_FOLDERS = 28
    const val MAX_NAME_LEN = 48
    const val ALL_ID = "all"
    /** Built-in list filters (not user-created folders; not cloud-synced). */
    const val SYSTEM_GROUPS_ID = "system:groups"
    const val SYSTEM_DIRECT_ID = "system:direct"
    const val SYSTEM_UNREAD_ID = "system:unread"
    const val SYSTEM_SECRET_ID = "system:secret"
    const val SYSTEM_LOCKED_ID = "system:locked"

    fun isSystemFilter(folderId: String?): Boolean =
        folderId == SYSTEM_GROUPS_ID ||
            folderId == SYSTEM_DIRECT_ID ||
            folderId == SYSTEM_UNREAD_ID ||
            folderId == SYSTEM_SECRET_ID ||
            folderId == SYSTEM_LOCKED_ID

    fun isUnreadChat(unreadCount: Int, markedUnread: Boolean): Boolean =
        unreadCount > 0 || markedUnread

    fun normalizeName(raw: String?): String =
        raw.orEmpty().trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LEN)

    fun canCreateMore(existingCount: Int): Boolean = existingCount < MAX_FOLDERS

    fun createFolder(
        existing: List<ChatFolder>,
        name: String,
        id: String = "folder_${UUID.randomUUID()}"
    ): List<ChatFolder>? {
        if (!canCreateMore(existing.size)) return null
        val cleaned = normalizeName(name)
        if (cleaned.isEmpty()) return null
        if (existing.any { it.name.equals(cleaned, ignoreCase = true) }) return null
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        return (existing + ChatFolder(id = id, name = cleaned, sortOrder = nextOrder))
            .sortedBy { it.sortOrder }
    }

    fun renameFolder(existing: List<ChatFolder>, folderId: String, name: String): List<ChatFolder>? {
        val cleaned = normalizeName(name)
        if (cleaned.isEmpty()) return null
        if (existing.any { it.id != folderId && it.name.equals(cleaned, ignoreCase = true) }) return null
        if (existing.none { it.id == folderId }) return null
        return existing.map {
            if (it.id == folderId) it.copy(name = cleaned) else it
        }
    }

    fun deleteFolder(existing: List<ChatFolder>, folderId: String): List<ChatFolder> =
        existing.filterNot { it.id == folderId }

    /**
     * 9.222：文件夹排序——与相邻文件夹交换 sortOrder（TG 式文件夹顺序自定义）。
     * delta = -1 上移，+1 下移；越界或不存在返回 null。
     */
    fun moveFolder(existing: List<ChatFolder>, folderId: String, delta: Int): List<ChatFolder>? {
        val sorted = existing.sortedBy { it.sortOrder }
        val index = sorted.indexOfFirst { it.id == folderId }
        if (index < 0) return null
        val target = index + delta
        if (target < 0 || target >= sorted.size) return null
        val a = sorted[index]
        val b = sorted[target]
        return sorted.map {
            when (it.id) {
                a.id -> it.copy(sortOrder = b.sortOrder)
                b.id -> it.copy(sortOrder = a.sortOrder)
                else -> it
            }
        }.sortedBy { it.sortOrder }
    }

    /**
     * 9.233：拖拽排序——把 [folderId] 移到排序后的 [targetIndex] 位（插入语义）。
     * 与 [moveFolder] 的交换语义不同：跨多位拖动时中间项依次前移/后移。
     * 重排后按位置重新连号 sortOrder，消除历史交换残留的间隔/碰撞。
     */
    fun reorderFolder(existing: List<ChatFolder>, folderId: String, targetIndex: Int): List<ChatFolder>? {
        val sorted = existing.sortedBy { it.sortOrder }
        val from = sorted.indexOfFirst { it.id == folderId }
        if (from < 0) return null
        val to = targetIndex.coerceIn(0, sorted.size - 1)
        if (from == to) return sorted
        val moved = sorted[from]
        val rest = sorted.filterIndexed { i, _ -> i != from }
        val rebuilt = rest.toMutableList().apply { add(to, moved) }
        return rebuilt.mapIndexed { i, folder -> folder.copy(sortOrder = i) }
    }

    fun setChatInFolder(
        existing: List<ChatFolder>,
        folderId: String,
        chatId: String,
        included: Boolean
    ): List<ChatFolder> {
        if (chatId.isBlank()) return existing
        return existing.map { folder ->
            if (folder.id != folderId) folder
            else {
                val ids = folder.chatIds.toMutableList()
                if (included) {
                    if (chatId !in ids) ids += chatId
                } else {
                    ids.remove(chatId)
                }
                folder.copy(chatIds = ids)
            }
        }
    }

    /** 将会话从所有文件夹移除后放入目标文件夹（单归属，避免重复计数）。 */
    fun moveChatToFolder(
        existing: List<ChatFolder>,
        chatId: String,
        targetFolderId: String?
    ): List<ChatFolder> {
        if (chatId.isBlank()) return existing
        return existing.map { folder ->
            val without = folder.chatIds.filterNot { it == chatId }
            if (targetFolderId != null && folder.id == targetFolderId) {
                folder.copy(chatIds = without + chatId)
            } else {
                folder.copy(chatIds = without)
            }
        }
    }

    fun folderOfChat(existing: List<ChatFolder>, chatId: String): ChatFolder? =
        existing.firstOrNull { chatId in it.chatIds }

    fun filterChatIds(folder: ChatFolder?, allChatIds: List<String>): List<String> {
        if (folder == null) return allChatIds
        val set = folder.chatIds.toSet()
        return allChatIds.filter { it in set }
    }

    fun unreadInFolder(
        folder: ChatFolder,
        unreadByChatId: Map<String, Int>
    ): Int = folder.chatIds.sumOf { (unreadByChatId[it] ?: 0).coerceAtLeast(0) }
}
