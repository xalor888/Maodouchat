package com.maodouchat.server.service

import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.repository.PostJson
import org.jetbrains.exposed.sql.ResultRow

/** B10：动态 feed/详情/评论/点赞者查询子域（可见性 + 社交图 + 批量聚合）。 */
class FeedQueryService {

    private val json = PostJson.json

    internal fun ResultRow.toPublicUser(): UserResponse {
        return UserResponse(
            id = this[Users.id],
            name = this[Users.name],
            email = "",
            avatar = this[Users.avatar],
            status = if (this[Users.showStatus]) this[Users.status] else "",
            isOnline = this[Users.showOnline] && this[Users.isOnline]
        )
    }
}
