package com.maodouchat.server.service

import com.maodouchat.server.db.PostImageClaims
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.repository.PostJson
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 图片占用（claim）管理：文件名→动态映射的查/建/回收。

internal fun PostCommandService.findPostIdByImageFilename(filename: String): String? {
    if (filename.isBlank() || !filename.matches(safeImageFilenamePattern) || !filename.startsWith("post_")) return null
    imageFilenameToPostId[filename]?.let { cachedPostId ->
        val stillClaimed = transaction {
            Posts.select(Posts.imageUrls).where { Posts.id eq cachedPostId }.limit(1).firstOrNull()
                ?.let { row -> PostJson.decodeImageUrls(row[Posts.imageUrls]).any { it.substringAfterLast('/') == filename } } == true
        }
        if (stillClaimed) return cachedPostId
        imageFilenameToPostId.remove(filename, cachedPostId)
    }
    return transaction {
        val claimedPostId = PostImageClaims.select(PostImageClaims.postId)
            .where { PostImageClaims.filename eq filename }
            .limit(1)
            .firstOrNull()
            ?.get(PostImageClaims.postId)
        if (claimedPostId != null) {
            val stillHas = Posts.select(Posts.imageUrls)
                .where { Posts.id eq claimedPostId }
                .firstOrNull()
                ?.let { row -> PostJson.decodeImageUrls(row[Posts.imageUrls]).any { it.substringAfterLast('/') == filename } }
                ?: false
            if (stillHas) {
                cacheImageClaim(filename, claimedPostId)
                return@transaction claimedPostId
            }
            // 清理异常残留的占用条目，避免永久命中已删动态。
            PostImageClaims.deleteWhere { PostImageClaims.filename eq filename }
        }
        // 文件名是 URL 尾段；imageUrls 为 JSON 数组字符串，用 LIKE 收窄后再精确匹配
        val needle = "%$filename%"
        Posts.select(Posts.id, Posts.imageUrls)
            .where { Posts.imageUrls like needle }
            .firstOrNull { row ->
                PostJson.decodeImageUrls(row[Posts.imageUrls]).any { url ->
                    url.substringAfterLast("/") == filename
                }
            }
            ?.get(Posts.id)
            ?.also { postId -> cacheImageClaim(filename, postId) }
    }
}

internal fun PostCommandService.cacheImageClaim(filename: String, postId: String) {
    synchronized(imageMetaCapacityLock) {
        if (imageFilenameToPostId.containsKey(filename) || imageFilenameToPostId.size < MAX_IMAGE_META_SIZE) {
            imageFilenameToPostId[filename] = postId
        }
    }
}

internal fun PostCommandService.deleteUnclaimedPostImage(filename: String, userId: String): Boolean = synchronized(imageClaimLock) {
    if (!FileStorageService.isOwnedPostImageFilename(filename, userId)) {
        return@synchronized false
    }
    transaction {
        val owner = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction false
        if (owner[Users.deletedAt] != null) return@transaction false
        if (findPostIdByImageFilename(filename) != null) return@transaction false
        FileStorageService.deletePostImage(filename)
    }
}

internal fun PostCommandService.allReferencedImageFilenames(): Set<String> = transaction {
    Posts.select(Posts.imageUrls).flatMapTo(linkedSetOf()) { row ->
        PostJson.decodeImageUrls(row[Posts.imageUrls]).map { it.substringAfterLast('/') }
    }
}

internal fun PostCommandService.deleteStaleUnreferencedImages(olderThan: Long): Int {
    val candidates = FileStorageService.listStalePostImageFiles(olderThan)
    var deleted = 0
    candidates.forEach { filename ->
        val removed = synchronized(imageClaimLock) {
            if (findPostIdByImageFilename(filename) == null) {
                FileStorageService.deletePostImage(filename)
            } else {
                false
            }
        }
        if (removed) deleted++
    }
    return deleted
}
