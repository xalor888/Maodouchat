package com.maodouchat.server.service

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostImageClaims
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.repository.PostJson
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 动态删除命令：用户自删、审核删除、按作者清删 + 行级清理。

internal fun PostCommandService.deletePost(postId: String, requesterId: String): Boolean {
    return synchronized(imageClaimLock) {
        val claimed = transaction {
            Users.selectAll().where { Users.id eq requesterId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val post = Posts.selectAll().where { Posts.id eq postId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (post[Posts.authorId] != requesterId) return@transaction null
            val claimed = PostJson.decodeImageUrls(post[Posts.imageUrls]).map { it.substringAfterLast('/') }
            if (!deletePostRow(postId)) return@transaction null
            claimed
        } ?: return@synchronized false
        cleanupDeletedPostImages(postId, claimed)
        true
    }
}

internal fun PostCommandService.deletePostForModeration(postId: String): Boolean {
    return synchronized(imageClaimLock) {
        val authorId = transaction {
            Posts.select(Posts.authorId).where { Posts.id eq postId }.firstOrNull()?.get(Posts.authorId)
        } ?: return@synchronized false
        val claimed = transaction {
            Users.selectAll().where { Users.id eq authorId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val post = Posts.selectAll().where { Posts.id eq postId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (post[Posts.authorId] != authorId) return@transaction null
            val claimed = PostJson.decodeImageUrls(post[Posts.imageUrls]).map { it.substringAfterLast('/') }
            if (!deletePostRow(postId)) return@transaction null
            claimed
        } ?: return@synchronized false
        cleanupDeletedPostImages(postId, claimed)
        true
    }
}

internal fun PostCommandService.deleteAllPostsForAuthor(authorId: String): Int = synchronized(imageClaimLock) {
    val deletedPosts = transaction {
        Users.selectAll().where { Users.id eq authorId }.forUpdate().firstOrNull()
            ?: return@transaction emptyList()
        val posts = Posts.selectAll().where { Posts.authorId eq authorId }.forUpdate().map { row ->
            row[Posts.id] to PostJson.decodeImageUrls(row[Posts.imageUrls]).map { it.substringAfterLast('/') }
        }
        posts.filter { (postId, _) -> deletePostRow(postId) }
    }
    deletedPosts.forEach { (postId, filenames) -> cleanupDeletedPostImages(postId, filenames) }
    deletedPosts.size
}

internal fun PostCommandService.deletePostRow(postId: String): Boolean {
    // 1.126：删除动态时一并清理其全部评论的点赞（孤儿行不再等 6h 兜底）
    val claimedFilenames = Posts.select(Posts.imageUrls)
        .where { Posts.id eq postId }
        .firstOrNull()
        ?.let { PostJson.decodeImageUrls(it[Posts.imageUrls]).map { url -> url.substringAfterLast('/') } }
        .orEmpty()
    val commentIds = PostComments.select(PostComments.id).where { PostComments.postId eq postId }.map { it[PostComments.id] }
    if (commentIds.isNotEmpty()) {
        CommentLikes.deleteWhere { CommentLikes.commentId inList commentIds }
    }
    PostComments.deleteWhere { PostComments.postId eq postId }
    PostLikes.deleteWhere { PostLikes.postId eq postId }
    if (claimedFilenames.isNotEmpty()) {
        PostImageClaims.deleteWhere { PostImageClaims.filename inList claimedFilenames }
    }
    return Posts.deleteWhere { Posts.id eq postId } > 0
}

internal fun PostCommandService.cleanupDeletedPostImages(postId: String, filenames: List<String>) {
    filenames.filter { it.startsWith("post_") }.forEach { filename ->
        imageFilenameToPostId.remove(filename, postId)
        // 历史多实例竞态可能留下重复引用；只在最后一个引用消失后删物理文件。
        if (findPostIdByImageFilename(filename) == null) {
            FileStorageService.deletePostImage(filename)
        }
    }
}
