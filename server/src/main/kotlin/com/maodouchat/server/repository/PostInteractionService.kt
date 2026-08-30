package com.maodouchat.server.repository

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** B10：动态/评论点赞交互子域（幂等 + 唯一约束冲突回读）。 */
class PostInteractionService {
    fun likePost(postId: String, userId: String): Boolean {
        // PG/H2：同事务 catch unique 后继续会 abort 整事务；冲突时外层 re-read
        return try {
            transaction {
                if (!PostVisibility.canViewInTransaction(postId, userId, lockPost = true)) return@transaction false
                val exists = !PostLikes.selectAll()
                    .where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
                    .empty()
                if (!exists) {
                    PostLikes.insert {
                        it[PostLikes.postId] = postId
                        it[PostLikes.userId] = userId
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
                true
            }
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
            transaction {
                if (!PostVisibility.canViewInTransaction(postId, userId, lockPost = true)) return@transaction false
                !PostLikes.selectAll()
                    .where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
                    .empty()
            }
        }
    }

    fun hasLiked(postId: String, userId: String): Boolean = transaction {
        !PostLikes.selectAll()
            .where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
            .empty()
    }

    fun unlikePost(postId: String, userId: String): Boolean {
        return transaction {
            val postExists = Posts.select(Posts.id).where { Posts.id eq postId }.limit(1).any()
            PostLikes.deleteWhere { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
            postExists
        }
    }

    fun likeComment(postId: String, commentId: String, userId: String): Pair<Int, Boolean> {
        // PG：并发点赞撞 (commentId, userId) 唯一约束会 abort 当前事务，同事务再查计数必 500；
        // catch 必须在事务外（Exposed 已回滚归还连接），冲突即「已被并发请求点赞成功」。
        return try {
            transaction {
                val comment = PostComments.selectAll().where { PostComments.id eq commentId }.limit(1).firstOrNull()
                    ?: return@transaction (-1 to false)
                if (comment[PostComments.postId] != postId) return@transaction (-1 to false)
                // 与 likePost 一致：评论所属动态对当前用户不可见（PRIVATE/CONTACTS/双向拉黑）时禁止点赞，
                // 避免越权交互与“评论是否存在”的探测 oracle
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
                // 胜者已提交点赞；本请求视为「已有赞」，返回最新计数
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

    fun unlikeComment(postId: String, commentId: String, userId: String): Int = transaction {
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

    fun commentLikeCount(commentId: String): Int =
        CommentLikes.select(CommentLikes.commentId)
            .where { CommentLikes.commentId eq commentId }
            .count()
            .toInt()

    /** 1.81：清理已删除评论的残留点赞（孤儿行）。 */
    fun purgeOrphanedCommentLikes(): Int = transaction {
        CommentLikes.deleteWhere {
            CommentLikes.commentId notInSubQuery PostComments.select(PostComments.id)
        }
    }

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = (cur.message ?: "").lowercase()
            if (cur is java.sql.SQLException && cur.sqlState == "23505") return true
            if (msg.contains("unique") || msg.contains("duplicate key")) return true
            cur = cur.cause
        }
        return false
    }

}
