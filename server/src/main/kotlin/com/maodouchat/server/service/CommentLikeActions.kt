package com.maodouchat.server.service

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.repository.PostVisibility
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 评论点赞：幂等写 + 计数；并发撞 (commentId, userId) 唯一约束会 abort 当前事务，
// 同事务再查计数必 500，所以 catch 必须在事务外，冲突即视为已被并发请求点赞成功。
fun PostInteractionService.likeComment(postId: String, commentId: String, userId: String): Pair<Int, Boolean> {
    return try {
        transaction {
            val comment = PostComments.selectAll().where { PostComments.id eq commentId }.limit(1).firstOrNull()
                ?: return@transaction (-1 to false)
            if (comment[PostComments.postId] != postId) return@transaction (-1 to false)
            // 评论所属动态对当前用户不可见时禁止点赞，避免越权交互与"评论是否存在"的探测。
            if (!PostVisibility.canViewInTransaction(comment[PostComments.postId], userId, lockPost = false)) {
                return@transaction (-1 to false)
            }
            val alreadyLiked = !CommentLikes.selectAll()
                .where { (CommentLikes.commentId eq commentId) and (CommentLikes.userId eq userId) }
                .limit(1)
                .empty()
            var newLike = false
            if (!alreadyLiked) {
                newLike = true
                CommentLikes.insert {
                    it[CommentLikes.commentId] = commentId
                    it[CommentLikes.userId] = userId
                    it[CommentLikes.createdAt] = System.currentTimeMillis()
                }
            }
            commentLikeCount(commentId) to newLike
        }
    } catch (e: Exception) {
        if (!isUniqueViolation(e)) throw e
        transaction {
            // 胜者已提交点赞；本请求视为"已有赞"，返回最新计数。
            val comment = PostComments.selectAll().where { PostComments.id eq commentId }.limit(1).firstOrNull()
                ?: return@transaction (-1 to false)
            if (comment[PostComments.postId] != postId ||
                !PostVisibility.canViewInTransaction(comment[PostComments.postId], userId, lockPost = false)
            ) {
                return@transaction (-1 to false)
            }
            commentLikeCount(commentId) to false
        }
    }
}

fun PostInteractionService.unlikeComment(postId: String, commentId: String, userId: String): Int = transaction {
    val comment = PostComments.selectAll().where { PostComments.id eq commentId }.limit(1).firstOrNull()
        ?: return@transaction -1
    if (comment[PostComments.postId] != postId) return@transaction -1
    if (!PostVisibility.canViewInTransaction(comment[PostComments.postId], userId, lockPost = false)) {
        return@transaction -1
    }
    CommentLikes.deleteWhere {
        (CommentLikes.commentId eq commentId) and (CommentLikes.userId eq userId)
    }
    commentLikeCount(commentId)
}

fun PostInteractionService.commentLikeCount(commentId: String): Int =
    CommentLikes.select(CommentLikes.commentId)
        .where { CommentLikes.commentId eq commentId }
        .count()
        .toInt()

// 清理已删除评论的残留点赞（孤儿行）。
fun PostInteractionService.purgeOrphanedCommentLikes(): Int = transaction {
    CommentLikes.deleteWhere {
        CommentLikes.commentId notInSubQuery PostComments.select(PostComments.id)
    }
}
