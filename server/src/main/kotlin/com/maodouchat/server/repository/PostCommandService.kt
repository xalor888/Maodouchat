package com.maodouchat.server.repository

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostImageClaims
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostCommentResponse
import com.maodouchat.server.model.PostResponse
import com.maodouchat.server.service.FileStorageService
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** B10：动态/评论写命令子域 + 图片 claim 管理。 */
class PostCommandService(
    private val feedQueryService: FeedQueryService,
) {
    // filename → postId 映射：图片先上传，post 后创建；创建时扫描 imageUrls 注册此映射。
    private val imageFilenameToPostId = ConcurrentHashMap<String, String>()
    private val imageClaimLock = Any()
    private val imageMetaCapacityLock = Any()
    private val MAX_IMAGE_META_SIZE = 10_000

    fun createPost(authorId: String, content: String, imageUrls: List<String>, visibility: String): PostResponse {
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

    private fun trimImageMetaIfNeeded() {
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

    private fun cacheImageClaim(filename: String, postId: String) {
        synchronized(imageMetaCapacityLock) {
            if (imageFilenameToPostId.containsKey(filename) || imageFilenameToPostId.size < MAX_IMAGE_META_SIZE) {
                imageFilenameToPostId[filename] = postId
            }
        }
    }

    fun deletePost(postId: String, requesterId: String): Boolean {
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

    fun updatePost(postId: String, requesterId: String, content: String, visibility: String?): PostResponse? {
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

    fun deletePostForModeration(postId: String): Boolean {
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

    fun deleteCommentForModeration(commentId: String): Boolean {
        return transaction {
            // 与用户自删一致：先置空子回复的 parentId，避免悬挂引用。
            PostComments.update({ PostComments.parentId eq commentId }) { it[parentId] = null }
            CommentLikes.deleteWhere { CommentLikes.commentId eq commentId }
            PostComments.deleteWhere { PostComments.id eq commentId } > 0
        }
    }

    fun deleteCommentForUser(postId: String, commentId: String, userId: String): Boolean {
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

    fun updateCommentForUser(
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

    fun findPostIdByImageFilename(filename: String): String? {
        if (filename.isBlank() || !filename.matches(Regex("^[A-Za-z0-9_.-]+$")) || !filename.startsWith("post_")) return null
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

    fun deleteUnclaimedPostImage(filename: String, userId: String): Boolean = synchronized(imageClaimLock) {
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

    fun allReferencedImageFilenames(): Set<String> = transaction {
        Posts.select(Posts.imageUrls).flatMapTo(linkedSetOf()) { row ->
            PostJson.decodeImageUrls(row[Posts.imageUrls]).map { it.substringAfterLast('/') }
        }
    }

    fun deleteStaleUnreferencedImages(olderThan: Long): Int {
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

    fun deleteAllPostsForAuthor(authorId: String): Int = synchronized(imageClaimLock) {
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

    private fun isImageFilenameClaimedInTx(filename: String): Boolean {
        if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) return true
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

    private fun deletePostRow(postId: String): Boolean {
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

    private fun cleanupDeletedPostImages(postId: String, filenames: List<String>) {
        filenames.filter { it.startsWith("post_") }.forEach { filename ->
            imageFilenameToPostId.remove(filename, postId)
            // 历史多实例竞态可能留下重复引用；只在最后一个引用消失后删物理文件。
            if (findPostIdByImageFilename(filename) == null) {
                FileStorageService.deletePostImage(filename)
            }
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
