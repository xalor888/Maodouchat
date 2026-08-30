package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.ConversationStateDeletion
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台「群聊管理」子域路由：群聊列表与解散。解散在单个事务内清理全部关联表
 * （引用表先删，遵守外键约束），磁盘附件清理作为事务外容错副作用。
 */
internal fun Route.configureAdminChatsRoutes() {
    get("/chats") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val groupOnly = call.request.queryParameters["groupOnly"] != "false"
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val chats = transaction {
            val query = Chats.selectAll()
            query.andWhere { Chats.chatType neq ChatType.SECRET }
            if (groupOnly) query.andWhere { Chats.isGroup eq true }
            val escapedSearch = search?.let { escapeLikePattern(it) }
            if (escapedSearch != null) query.andWhere { Chats.groupName like "%$escapedSearch%" }
            val rows = query
                .orderBy(Chats.memberRevision to SortOrder.DESC, Chats.id to SortOrder.DESC)
                .limit(limit, offset)
                .toList()
            val chatIds = rows.map { it[Chats.id] }
            val countExpr = ChatParticipants.userId.count()
            val memberCounts: Map<String, Int> = if (chatIds.isEmpty()) emptyMap() else
                ChatParticipants.slice(ChatParticipants.chatId, countExpr)
                    .selectAll().where { ChatParticipants.chatId inList chatIds }
                    .groupBy(ChatParticipants.chatId)
                    .associate { it[ChatParticipants.chatId] to it[countExpr].toInt() }
            val lastMsgMap: Map<String, Long> = if (chatIds.isEmpty()) emptyMap() else {
                val maxExpr = MessagingV2Messages.serverTimestamp.max()
                MessagingV2Messages.slice(MessagingV2Messages.conversationId, maxExpr)
                    .selectAll().where {
                        (MessagingV2Messages.conversationId inList chatIds) and
                            (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
                    }
                    .groupBy(MessagingV2Messages.conversationId)
                    .associate { it[MessagingV2Messages.conversationId] to (it[maxExpr] ?: 0L) }
            }
            rows.map { row ->
                val chatId = row[Chats.id]
                ChatAdminResponse(
                    id = chatId,
                    isGroup = row[Chats.isGroup],
                    chatType = row[Chats.chatType],
                    groupName = row[Chats.groupName],
                    groupAnnouncement = row[Chats.groupAnnouncement],
                    memberCount = memberCounts[chatId] ?: 0,
                    createdAt = 0L,
                    lastActivity = lastMsgMap[chatId] ?: 0L
                )
            }
        }
        call.respond(chats)
    }

    delete("/chats/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少聊天 ID"))
        val (status, attachmentIds, groupAvatarUrl) = transaction {
            val chat = Chats.selectAll().where { Chats.id eq id }.forUpdate().firstOrNull()
                ?: return@transaction Triple("missing", emptyList<String>(), null as String?)
            if (!chat[Chats.isGroup] || chat[Chats.chatType] == ChatType.SECRET) {
                return@transaction Triple("forbidden", emptyList<String>(), null)
            }
            // 统一走 ConversationStateDeletion 级联清理（含邀请、投票、Messaging V2 等全部关联表），
            // 避免管理端绕过领域删除规则、漏删 GroupInvitations/GroupPolls 造成孤儿数据。
            val attachmentIds = ConversationStateDeletion.deleteConversation(id)
            Triple("ok", attachmentIds, chat[Chats.groupAvatar])
        }
        if (status == "missing") return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
        if (status != "ok") return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("只能解散群聊或频道"))
        // 事务外清磁盘，避免 orphan .bin
        attachmentIds.forEach { runCatching { com.maodouchat.server.service.EncryptedAttachmentStorage.delete(it) } }
        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(groupAvatarUrl, id)
        recordAdminAudit(actorId, "ADMIN_CHAT_DISSOLVED", "chatId=$id")
        call.respond(
            buildJsonObject {
                put("status", "dissolved")
            }
        )
    }
}
