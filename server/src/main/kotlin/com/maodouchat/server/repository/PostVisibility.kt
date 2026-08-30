package com.maodouchat.server.repository

import com.maodouchat.server.db.Posts
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

/** B10：动态可见性判定（社交图 + 统一策略），供 command / query / interaction 复用。 */
internal object PostVisibility {
    fun canViewInTransaction(postId: String, userId: String, lockPost: Boolean): Boolean {
        val contactIds = SocialGraphService.contactIds(userId)
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(userId)
        val query = Posts.selectAll().where { Posts.id eq postId }
        val row = (if (lockPost) query.forUpdate().firstOrNull() else query.firstOrNull())
            ?: return false
        return VisibilityPolicy.isVisible(
            row[Posts.visibility], row[Posts.authorId], userId, contactIds, blockedUserIds,
        )
    }
}
