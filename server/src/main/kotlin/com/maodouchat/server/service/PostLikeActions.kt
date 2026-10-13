package com.maodouchat.server.service

import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.repository.PostVisibility
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 动态点赞：幂等写；PG/H2 下同事务 catch 唯一约束冲突会 abort 整事务，冲突时外层重读。
fun PostInteractionService.likePost(postId: String, userId: String): Boolean {
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

fun PostInteractionService.hasLiked(postId: String, userId: String): Boolean = transaction {
    !PostLikes.selectAll()
        .where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
        .empty()
}

fun PostInteractionService.unlikePost(postId: String, userId: String): Boolean {
    return transaction {
        val postExists = Posts.select(Posts.id).where { Posts.id eq postId }.limit(1).any()
        PostLikes.deleteWhere { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
        postExists
    }
}
