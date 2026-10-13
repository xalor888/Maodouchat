package com.maodouchat.server.service

// 动态帖子可见性簇：可见性与作者判定（原 FeedPostQueries 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Posts
import com.maodouchat.server.repository.PostVisibility
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun FeedQueryService.canView(postId: String, userId: String): Boolean {
    return transaction {
        PostVisibility.canViewInTransaction(postId, userId, lockPost = false)
    }
}


fun FeedQueryService.isAuthor(postId: String, userId: String): Boolean {
    return transaction {
        Posts.selectAll().where { Posts.id eq postId }
            .firstOrNull()
            ?.get(Posts.authorId) == userId
    }
}


fun FeedQueryService.getPostAuthorId(postId: String): String? {
    return transaction {
        Posts.selectAll().where { Posts.id eq postId }.firstOrNull()?.get(Posts.authorId)
    }
}


fun FeedQueryService.exists(postId: String): Boolean {
    return transaction {
        !Posts.selectAll().where { Posts.id eq postId }.empty()
    }
}
