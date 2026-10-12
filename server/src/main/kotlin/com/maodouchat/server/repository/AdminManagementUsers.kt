package com.maodouchat.server.repository

// 用户管理簇（用户列表/详情/push token）：原 AdminManagementRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.common.toUserAdminResponse
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.PushTokenAdminResponse
import com.maodouchat.server.model.UserAdminResponse
import com.maodouchat.server.model.UserDetailAdminResponse
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminManagementRepository.listUsers(
    limit: Int,
    offset: Long,
    search: String?,
    status: String?,
): List<UserAdminResponse> = transaction {
    val escapedSearch = search?.let { escapeLikePattern(it) }
    val base = if (escapedSearch != null) {
        val likePattern = LikePattern("%$escapedSearch%", '\\')
        Users.selectAll().where {
            (Users.name like likePattern) or (Users.email like likePattern)
        }
    } else {
        Users.selectAll()
    }
    val now = System.currentTimeMillis()
    val filtered = when (status) {
        "active" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil lessEq now) }
        "banned" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil greater now) }
        "deleted" -> base.andWhere { Users.deletedAt.isNotNull() }
        "online" -> base.andWhere { Users.deletedAt.isNull() and (Users.isOnline eq true) }
        // 无关键字的默认列表隐藏已注销；带 q 时包含 tombstone，方便按 deleted_ 邮箱找回。
        else -> if (escapedSearch != null) base else base.andWhere { Users.deletedAt.isNull() }
    }
    filtered.orderBy(Users.lastSeen to SortOrder.DESC, Users.id to SortOrder.DESC)
        .limit(limit, offset)
        .map { it.toUserAdminResponse() }
}

fun AdminManagementRepository.getUserAdmin(id: String): UserAdminResponse? = transaction {
    Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUserAdminResponse()
}

fun AdminManagementRepository.getUserDetail(id: String): UserDetailAdminResponse? = transaction {
    val row = Users.selectAll().where { Users.id eq id }.firstOrNull() ?: return@transaction null
    UserDetailAdminResponse(
        id = row[Users.id],
        name = row[Users.name],
        email = row[Users.email],
        isModerator = row[Users.isModerator],
        lastActiveAt = row[Users.lastSeen],
        suspendedUntil = row[Users.suspendedUntil],
        postRestrictedUntil = row[Users.postRestrictedUntil],
        messageRestrictedUntil = row[Users.messageRestrictedUntil],
        deletedAt = row[Users.deletedAt],
        messageCount = MessagingV2Messages.selectAll().where {
            (MessagingV2Messages.senderUserId eq id) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }.count(),
        postCount = Posts.selectAll().where { Posts.authorId eq id }.count(),
        commentCount = PostComments.selectAll().where { PostComments.authorId eq id }.count(),
        chatCount = ChatParticipants.selectAll().where { ChatParticipants.userId eq id }.count(),
        pushTokenCount = PushTokens.selectAll().where { PushTokens.userId eq id }.count(),
        reportCount = Reports.selectAll().where {
            (Reports.reporterId eq id) or (Reports.targetId eq id)
        }.count(),
        avatar = row[Users.avatar],
    )
}

/** 管理后台推送令牌列表（只含元数据，绝不含推送内容）。可选按 userId 精确过滤。 */
fun AdminManagementRepository.listPushTokens(limit: Int, offset: Long, userIdFilter: String?): List<PushTokenAdminResponse> = transaction {
    val query = PushTokens.selectAll()
    if (!userIdFilter.isNullOrBlank()) query.andWhere { PushTokens.userId eq userIdFilter }
    query.orderBy(
        PushTokens.updatedAt to SortOrder.DESC,
        PushTokens.userId to SortOrder.DESC,
        PushTokens.deviceId to SortOrder.DESC,
    )
        .limit(limit, offset)
        .map {
            PushTokenAdminResponse(
                userId = it[PushTokens.userId],
                deviceId = it[PushTokens.deviceId],
                platform = it[PushTokens.platform],
                timezoneOffsetMinutes = it[PushTokens.timezoneOffsetMinutes],
                updatedAt = it[PushTokens.updatedAt],
            )
        }
}
