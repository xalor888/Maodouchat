package com.maodouchat.server.service

import com.maodouchat.server.db.PostImageClaims
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostResponse
import com.maodouchat.server.repository.PostJson
import com.maodouchat.server.repository.VisibilityPolicy
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

// 动态写命令：创建（含图片占用声明）与更新。

internal fun PostCommandService.createPost(authorId: String, content: String, imageUrls: List<String>, visibility: String): PostResponse {
    val created = synchronized(imageClaimLock) {
        val filenames = imageUrls.map { it.substringAfterLast('/') }
        val created = transaction {
            val author = Users.selectAll().where { Users.id eq authorId }.forUpdate().limit(1).firstOrNull()
            require(author != null && author[Users.deletedAt] == null) { "账号已注销" }
            require(imageUrls.all { FileStorageService.isOwnedPostImageUrl(it, authorId) }) {
                "动态图片不存在或不属于当前账号"
            }
            // 8.48 修复 M13：热路径查进程内占用缓存，冷启动/多实例以 DB 唯一占用表为准；
            // 避免服务重启后同一张图片被重复发布（删一条会破坏另一条动态的图片）。
            require(filenames.none(::isImageFilenameClaimedInTx)) { "动态图片已被其他动态使用" }
            val postId = "p_${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            Posts.insert {
                it[id] = postId
                it[Posts.authorId] = authorId
                it[Posts.content] = content
                it[Posts.imageUrls] = PostJson.json.encodeToString(PostJson.imageUrlListSerializer, imageUrls)
                it[Posts.visibility] = VisibilityPolicy.normalize(visibility)
                it[createdAt] = now
            }
            filenames.forEach { filename ->
                try {
                    PostImageClaims.insert {
                        it[PostImageClaims.filename] = filename
                        it[PostImageClaims.postId] = postId
                        it[PostImageClaims.claimedAt] = now
                    }
                } catch (conflict: ExposedSQLException) {
                    if (isUniqueViolation(conflict)) {
                        throw IllegalArgumentException("动态图片已被其他动态使用")
                    }
                    throw conflict
                }
            }
            feedQueryService.getPostById(postId, authorId)!!
        }
        filenames.forEach { filename -> cacheImageClaim(filename, created.id) }
        created
    }
    // 锁外执行容量裁剪：trim 需要全量回查 DB，不得持全局图片锁阻塞发帖/删帖。
    trimImageMetaIfNeeded()
    return created
}

internal fun PostCommandService.updatePost(postId: String, requesterId: String, content: String, visibility: String?): PostResponse? {
    return transaction {
        val post = Posts.selectAll().where { Posts.id eq postId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (post[Posts.authorId] != requesterId) return@transaction null
        val now = System.currentTimeMillis()
        val updated = Posts.update({
            (Posts.id eq postId) and (Posts.authorId eq requesterId)
        }) {
            it[Posts.content] = content.trim()
            if (visibility != null) it[Posts.visibility] = VisibilityPolicy.normalize(visibility)
            it[Posts.editedAt] = now
        }
        if (updated != 1) return@transaction null
        feedQueryService.getPostById(postId, requesterId)
    }
}

internal fun PostCommandService.isImageFilenameClaimedInTx(filename: String): Boolean {
    if (!filename.matches(safeImageFilenamePattern)) return true
    if (imageFilenameToPostId[filename] != null) return true
    if (PostImageClaims.select(PostImageClaims.postId)
            .where { PostImageClaims.filename eq filename }
            .limit(1)
            .any()
    ) {
        return true
    }
    // 升级前的旧动态没有占用行，回退 Posts.imageUrls LIKE 精确匹配，防止存量图片被重复引用。
    val needle = "%$filename%"
    return Posts.select(Posts.id, Posts.imageUrls)
        .where { Posts.imageUrls like needle }
        .firstOrNull { row ->
            PostJson.decodeImageUrls(row[Posts.imageUrls]).any { url ->
                url.substringAfterLast("/") == filename
            }
        } != null
}

internal fun PostCommandService.trimImageMetaIfNeeded() {
    if (imageFilenameToPostId.size <= MAX_IMAGE_META_SIZE) return
    val existingPostIds = transaction {
        Posts.select(Posts.id).map { it[Posts.id] }.toSet()
    }
    imageFilenameToPostId.entries.removeIf { it.value !in existingPostIds }
    val overflow = (imageFilenameToPostId.size - MAX_IMAGE_META_SIZE).coerceAtLeast(0)
    imageFilenameToPostId.keys.asSequence().take(overflow).toList().forEach { filename ->
        imageFilenameToPostId.remove(filename)
    }
}
