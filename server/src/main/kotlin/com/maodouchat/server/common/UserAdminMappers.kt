package com.maodouchat.server.common

import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostAdminResponse
import com.maodouchat.server.model.UserAdminResponse
import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toUserAdminResponse(): UserAdminResponse = UserAdminResponse(
    id = this[Users.id],
    name = this[Users.name],
    email = this[Users.email],
    isModerator = this[Users.isModerator],
    lastActiveAt = this[Users.lastSeen],
    suspendedUntil = this[Users.suspendedUntil],
    postRestrictedUntil = this[Users.postRestrictedUntil],
    messageRestrictedUntil = this[Users.messageRestrictedUntil],
    deletedAt = this[Users.deletedAt]
)

fun ResultRow.toPostAdminResponse(authorName: String): PostAdminResponse = PostAdminResponse(
    id = this[Posts.id],
    authorId = this[Posts.authorId],
    authorName = authorName,
    content = this[Posts.content],
    status = this[Posts.status],
    createdAt = this[Posts.createdAt]
)
