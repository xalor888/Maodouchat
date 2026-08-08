package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatFolders
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatFolderDto
import com.maodouchat.server.model.ChatFoldersSyncRequest
import com.maodouchat.server.model.ChatFoldersSyncResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class ChatFolderRepository {

    private val json = Json { ignoreUnknownKeys = true }

    fun getFolders(userId: String): ChatFoldersSyncResponse = transaction {
        loadFoldersInTransaction(userId)
    }

    private fun loadFoldersInTransaction(userId: String): ChatFoldersSyncResponse {
        val storedFolders = ChatFolders.selectAll()
            .where { ChatFolders.userId eq userId }
            .orderBy(ChatFolders.sortOrder to SortOrder.ASC)
            .map { row ->
                ChatFolderDto(
                    id = row[ChatFolders.folderId],
                    name = row[ChatFolders.name],
                    sortOrder = row[ChatFolders.sortOrder],
                    chatIds = decodeChatIds(row[ChatFolders.chatIdsJson]),
                    updatedAt = row[ChatFolders.updatedAt]
                )
            }
        val storedChatIds = storedFolders.flatMap(ChatFolderDto::chatIds).distinct()
        val memberChatIds = memberChatIdsInTransaction(userId, storedChatIds)
        val visibleFolders = storedFolders.map { folder ->
            folder.copy(chatIds = folder.chatIds.filter(memberChatIds::contains))
        }
        val maxUpdated = visibleFolders.maxOfOrNull { it.updatedAt } ?: 0L
        return ChatFoldersSyncResponse(folders = visibleFolders, updatedAt = maxUpdated)
    }

    /**
     * 全量替换当前用户的云端文件夹（客户端本地为权威合并后推送）。
     */
    fun replaceFolders(userId: String, request: ChatFoldersSyncRequest): ChatFoldersSyncResponse = transaction {
        val owner = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction ChatFoldersSyncResponse(emptyList(), 0L)
        if (owner[Users.deletedAt] != null) return@transaction ChatFoldersSyncResponse(emptyList(), 0L)
        val now = System.currentTimeMillis()
        val normalizedInput = request.folders
            .asSequence()
            .mapNotNull { folder ->
                val id = folder.id.trim().take(80)
                val name = folder.name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LEN)
                if (id.isEmpty() || name.isEmpty()) null
                else ChatFolderDto(
                    id = id,
                    name = name,
                    sortOrder = folder.sortOrder.coerceIn(0, 999),
                    chatIds = folder.chatIds
                        .map(String::trim)
                        .filter(::isValidChatId)
                        .distinct()
                        .take(MAX_CHATS_PER_FOLDER),
                    updatedAt = now
                )
            }
            .distinctBy(ChatFolderDto::id)
            .take(MAX_FOLDERS)
            .toList()
        val requestedChatIds = normalizedInput.flatMap(ChatFolderDto::chatIds).distinct()
        val memberChatIds = memberChatIdsInTransaction(userId, requestedChatIds, lockRows = true)
        val normalized = normalizedInput
            .map { folder -> folder.copy(chatIds = folder.chatIds.filter(memberChatIds::contains)) }
            .sortedBy { it.sortOrder }

        ChatFolders.deleteWhere { ChatFolders.userId eq userId }
        normalized.forEach { folder ->
            ChatFolders.insert {
                it[ChatFolders.userId] = userId
                it[folderId] = folder.id
                it[name] = folder.name
                it[sortOrder] = folder.sortOrder
                it[chatIdsJson] = json.encodeToString(folder.chatIds)
                it[updatedAt] = now
            }
        }
        loadFoldersInTransaction(userId)
    }

    private fun memberChatIdsInTransaction(
        userId: String,
        chatIds: List<String>,
        lockRows: Boolean = false
    ): Set<String> {
        if (chatIds.isEmpty()) return emptySet()
        val query = ChatParticipants.select(ChatParticipants.chatId)
            .where {
                (ChatParticipants.userId eq userId) and
                    (ChatParticipants.chatId inList chatIds)
            }
            .orderBy(ChatParticipants.chatId to SortOrder.ASC)
        val rows = if (lockRows) query.forUpdate().toList() else query.toList()
        return rows.mapTo(hashSetOf()) { it[ChatParticipants.chatId] }
    }

    private fun decodeChatIds(raw: String): List<String> = runCatching {
        json.decodeFromString<List<String>>(raw)
            .map { it.trim() }
            .filter(::isValidChatId)
            .distinct()
    }.getOrDefault(emptyList())

    private fun isValidChatId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    companion object {
        private const val MAX_FOLDERS = 28
        private const val MAX_NAME_LEN = 48
        private const val MAX_CHATS_PER_FOLDER = 500
    }
}
