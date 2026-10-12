package com.maodouchat.server.service

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.model.PostCommentResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 评论写命令：审核删除、用户自删、用户编辑。

internal fun PostCommandService.deleteCommentForModeration(commentId: String): Boolean {
    return transaction {
        // 与用户自删一致：先置空子回复的 parentId，避免悬挂引用。
        PostComments.update({ PostComments.parentId eq commentId }) { it[parentId] = null }
        CommentLikes.deleteWhere { CommentLikes.commentId eq commentId }
        PostComments.deleteWhere { PostComments.id eq commentId } > 0
    }
}

internal fun PostCommandService.deleteCommentForUser(postId: String, commentId: String, userId: String): Boolean {
    return transaction {
        val comment = PostComments.selectAll().where { PostComments.id eq commentId }.forUpdate().firstOrNull()
            ?: return@transaction false
        if (comment[PostComments.authorId] != userId || comment[PostComments.postId] != postId) return@transaction false
        // 1.79：删除评论时其回复的 parentId 一并置空（避免悬挂引用）
        PostComments.update({ PostComments.parentId eq commentId }) { it[parentId] = null }
        // 1.126：删除评论时清理其点赞（孤儿行不再等 6h 兜底）
        CommentLikes.deleteWhere { CommentLikes.commentId eq commentId }
        PostComments.deleteWhere { PostComments.id eq commentId } > 0
    }
}

internal fun PostCommandService.updateCommentForUser(
    commentId: String,
    postId: String,
    userId: String,
    content: String
): PostCommentResponse? {
    return transaction {
        val comment = PostComments.selectAll().where { PostComments.id eq commentId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (comment[PostComments.authorId] != userId || comment[PostComments.postId] != postId) {
            return@transaction null
        }
        PostComments.update({ PostComments.id eq commentId }) {
            it[PostComments.content] = content
        }
        feedQueryService.getCommentById(commentId, userId)
    }
}
