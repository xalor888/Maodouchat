package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostCommentResponse
import com.maodouchat.server.model.PostResponse
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.service.FileStorageService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PostRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val imageUrlListSerializer = ListSerializer(String.serializer())
    // filename → postId 映射：图片先上传，post 后创建；创建时扫描 imageUrls 注册此映射。
    // 设上限防内存泄漏：超过 10000 条时，清理已删除动态的映射条目。
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
                // 8.48 修复 M13：刚上传的 owned 图片（文件名 UUID 唯一）不可能已被其他动态
                // 使用——只查进程内占用缓存（imageClaimLock 已原子），跳过 DB LIKE 全表扫
                //（此前每张新图缓存 miss 后逐张 LIKE 全表扫，posts 越大越慢）。
                // 多实例并发的 DB 级唯一约束见 L2 记录（单实例由 imageClaimLock 保证）。
                require(filenames.none { imageFilenameToPostId[it] != null }) { "动态图片已被其他动态使用" }
                val postId = "p_${UUID.randomUUID()}"
                val now = System.currentTimeMillis()
                Posts.insert {
                    it[id] = postId
                    it[Posts.authorId] = authorId
                    it[Posts.content] = content
                    it[Posts.imageUrls] = json.encodeToString(imageUrlListSerializer, imageUrls)
                    it[Posts.visibility] = normalizeVisibility(visibility)
                    it[createdAt] = now
                }
                getPostById(postId, authorId)!!
            }
            filenames.forEach { filename -> cacheImageClaim(filename, created.id) }
            created
        }
        // 锁外执行容量裁剪：trim 需要全量回查 DB，不得持全局图片锁阻塞发帖/删帖。
        trimImageMetaIfNeeded()
        return created
    }

    /**
     * 批量聚合指定动态 ID 列表的 likeCount / commentCount / likedByMe。
     * 解决 toPostResponse 的 N+1 查询问题：原来每发一个动态就发 3 条 SQL，
     * 聚合后整个 feed 只需 3 条 SQL（IN + GROUP BY）。
     * 使用 Exposed 0.46 支持的 Query.select(columns).where{}.groupBy() 聚合写法。
     */
    private fun batchAggregatePostMeta(postIds: List<String>, currentUserId: String): PostMeta {
        if (postIds.isEmpty()) return PostMeta(emptyMap(), emptyMap(), emptySet())
        val likeCountExpr = PostLikes.userId.count()
        val likeCounts = PostLikes
            .select(PostLikes.postId, likeCountExpr)
            .where { PostLikes.postId inList postIds }
            .groupBy(PostLikes.postId)
            .associate { it[PostLikes.postId] to it[likeCountExpr].toInt() }
        val commentCountExpr = PostComments.id.count()
        val commentCounts = PostComments
            .select(PostComments.postId, commentCountExpr)
            .where { PostComments.postId inList postIds }
            .groupBy(PostComments.postId)
            .associate { it[PostComments.postId] to it[commentCountExpr].toInt() }
        val likedByMe = PostLikes
            .select(PostLikes.postId)
            .where { (PostLikes.postId inList postIds) and (PostLikes.userId eq currentUserId) }
            .map { it[PostLikes.postId] }
            .toSet()
        return PostMeta(likeCounts, commentCounts, likedByMe)
    }

    private data class PostMeta(
        val likeCounts: Map<String, Int>,
        val commentCounts: Map<String, Int>,
        val likedByMe: Set<String>
    )

    private fun ResultRow.toPostResponse(currentUserId: String, meta: PostMeta): PostResponse {
        val postId = this[Posts.id]
        return PostResponse(
            id = postId,
            author = toPublicUser(),
            content = this[Posts.content],
            imageUrls = decodeImageUrls(this[Posts.imageUrls]),
            visibility = normalizeVisibility(this[Posts.visibility]),
            createdAt = this[Posts.createdAt],
            editedAt = this[Posts.editedAt],
            likeCount = meta.likeCounts[postId] ?: 0,
            commentCount = meta.commentCounts[postId] ?: 0,
            likedByMe = postId in meta.likedByMe,
            isMine = this[Posts.authorId] == currentUserId
        )
    }

    /** 内存保护：先清理已删除动态，再淘汰可回查 DB 的缓存项直到硬上限。 */
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

    fun getFeed(
        currentUserId: String,
        limit: Int = 20,
        before: Long? = null,
        beforeId: String? = null
    ): List<PostResponse> {
        val boundedLimit = limit.coerceIn(1, 50) // BUG 3.2 fix: 仓库层也限制上限
        return transaction {
            val contactIds = getContactIds(currentUserId)
            val blockedUserIds = getBlockedEitherWayUserIds(currentUserId)
            // 分批拉取：每次拉 boundedLimit*3 条候选，过滤可见后取 boundedLimit 条；
            // 若不足则继续拉取下一批，最多 20 次（避免极端分布下拉太多）
            val result = mutableListOf<PostResponse>()
            var batchBefore: Long? = before
            var batchBeforeId: String? = beforeId
            var iterations = 0
            while (result.size < boundedLimit && iterations < 20) {
                val batchSize = ((boundedLimit - result.size) * 5).coerceAtLeast(boundedLimit)
                val cursorQuery = (Posts innerJoin Users)
                    .selectAll()
                    .let { base ->
                        val cursor = batchBefore
                        val cursorId = batchBeforeId
                        when {
                            cursor == null -> base.where { Users.deletedAt.isNull() }
                            cursorId.isNullOrBlank() -> base.where {
                                (Posts.createdAt less cursor) and Users.deletedAt.isNull()
                            }
                            else -> base.where {
                                (Posts.createdAt less cursor) or
                                    ((Posts.createdAt eq cursor) and (Posts.id less cursorId))
                            }.andWhere { Users.deletedAt.isNull() }
                        }
                    }
                val visibleAuthors = contactIds + currentUserId
                val visibilityQuery = cursorQuery.andWhere {
                    (Posts.authorId eq currentUserId) or
                        (Posts.visibility eq "PUBLIC") or
                        ((Posts.visibility eq "CONTACTS") and (Posts.authorId inList visibleAuthors))
                }
                val finalQuery = if (blockedUserIds.isEmpty()) {
                    visibilityQuery
                } else {
                    visibilityQuery.andWhere { Posts.authorId notInList blockedUserIds }
                }
                val batchQuery = finalQuery
                    .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                    .limit(batchSize)

                val batch = batchQuery.toList()
                if (batch.isEmpty()) break

                // 先过滤可见，再批量聚合 meta（整个 batch 一次性 SQL 聚合）
                val visibleRows = batch.filter { it.isPostVisibleTo(currentUserId, contactIds, blockedUserIds) }
                val visibleIds = visibleRows.map { it[Posts.id] }
                val meta = batchAggregatePostMeta(visibleIds, currentUserId)

                for (row in visibleRows) {
                    if (result.size >= boundedLimit) break
                    result.add(row.toPostResponse(currentUserId, meta))
                }
                val last = batch.last()
                batchBefore = last[Posts.createdAt]
                batchBeforeId = last[Posts.id]
                iterations++
            }
            result
        }
    }

    fun getPostById(postId: String, currentUserId: String): PostResponse? {
        return transaction {
            val contactIds = getContactIds(currentUserId)
            val blockedUserIds = getBlockedEitherWayUserIds(currentUserId)
            val row = (Posts innerJoin Users)
                .selectAll()
                .where { Posts.id eq postId }
                .firstOrNull()
                ?.takeIf { it.isPostVisibleTo(currentUserId, contactIds, blockedUserIds) } ?: return@transaction null
            // 单条路径同样走聚合路径（1 条 IN 查询比 3 条 count()/empty() 快）
            val meta = batchAggregatePostMeta(listOf(postId), currentUserId)
            row.toPostResponse(currentUserId, meta)
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
                val claimed = decodeImageUrls(post[Posts.imageUrls]).map { it.substringAfterLast('/') }
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
                if (visibility != null) it[Posts.visibility] = normalizeVisibility(visibility)
                it[Posts.editedAt] = now
            }
            if (updated != 1) return@transaction null
            getPostById(postId, requesterId)
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
                val claimed = decodeImageUrls(post[Posts.imageUrls]).map { it.substringAfterLast('/') }
                if (!deletePostRow(postId)) return@transaction null
                claimed
            } ?: return@synchronized false
            cleanupDeletedPostImages(postId, claimed)
            true
        }
    }

    fun deleteCommentForModeration(commentId: String): Boolean {
        return transaction {
            PostComments.deleteWhere { PostComments.id eq commentId } > 0
        }
    }

    /**
     * 用户自助删除本人评论（被遗忘权/隐私）。仅当评论作者为 [userId] 时成功，
     * 否则返回 false（对应路由返回 404，避免泄露评论归属）。
     */
    fun deleteCommentForUser(commentId: String, userId: String): Boolean {
        return transaction {
            val comment = PostComments.selectAll().where { PostComments.id eq commentId }.forUpdate().firstOrNull()
                ?: return@transaction false
            if (comment[PostComments.authorId] != userId) return@transaction false
            // 1.79：删除评论时其回复的 parentId 一并置空（避免悬挂引用）
            PostComments.update({ PostComments.parentId eq commentId }) { it[parentId] = null }
            // 1.126：删除评论时清理其点赞（孤儿行不再等 6h 兜底）
            CommentLikes.deleteWhere { CommentLikes.commentId eq commentId }
            PostComments.deleteWhere { PostComments.id eq commentId } > 0
        }
    }

    /**
     * 用户自助编辑本人评论。路由层已做内容校验与审核；此处校验作者和所属动态后更新。
     */
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
            getCommentById(commentId, userId)
        }
    }

    /**
     * 根据图片文件名查找所属动态 ID。
     * 内存映射为热路径缓存；miss 时回查 DB（进程重启 / 多实例后仍可用）。
     */
    fun findPostIdByImageFilename(filename: String): String? {
        if (filename.isBlank() || !filename.startsWith("post_")) return null
        imageFilenameToPostId[filename]?.let { cachedPostId ->
            val stillClaimed = transaction {
                Posts.select(Posts.imageUrls).where { Posts.id eq cachedPostId }.limit(1).firstOrNull()
                    ?.let { row -> decodeImageUrls(row[Posts.imageUrls]).any { it.substringAfterLast('/') == filename } } == true
            }
            if (stillClaimed) return cachedPostId
            imageFilenameToPostId.remove(filename, cachedPostId)
        }
        return transaction {
            // 文件名是 URL 尾段；imageUrls 为 JSON 数组字符串，用 LIKE 收窄后再精确匹配
            val needle = "%$filename%"
            Posts.select(Posts.id, Posts.imageUrls)
                .where { Posts.imageUrls like needle }
                .firstOrNull { row ->
                    decodeImageUrls(row[Posts.imageUrls]).any { url ->
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
            decodeImageUrls(row[Posts.imageUrls]).map { it.substringAfterLast('/') }
        }
    }

    // 8.50 修复 M2：锁内仅取引用快照（保证与 claim 一致），磁盘 list+删除移到锁外——
    // 此前持全局 imageClaimLock 做全表扫描 + 物理删文件，大表/大文件时阻塞所有发帖删帖
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
                row[Posts.id] to decodeImageUrls(row[Posts.imageUrls]).map { it.substringAfterLast('/') }
            }
            posts.filter { (postId, _) -> deletePostRow(postId) }
        }
        deletedPosts.forEach { (postId, filenames) -> cleanupDeletedPostImages(postId, filenames) }
        deletedPosts.size
    }

    fun isAuthor(postId: String, userId: String): Boolean {
        return transaction {
            Posts.selectAll().where { Posts.id eq postId }
                .firstOrNull()
                ?.get(Posts.authorId) == userId
        }
    }

    fun getPostAuthorId(postId: String): String? {
        return transaction {
            Posts.selectAll().where { Posts.id eq postId }.firstOrNull()?.get(Posts.authorId)
        }
    }

    fun getCommentAuthorId(commentId: String): String? {
        return transaction {
            PostComments.selectAll().where { PostComments.id eq commentId }.firstOrNull()?.get(PostComments.authorId)
        }
    }

    fun exists(postId: String): Boolean {
        return transaction {
            !Posts.selectAll().where { Posts.id eq postId }.empty()
        }
    }

    /** 作者主页：返回指定用户全部可见的动态（含自己 PRIVATE、他人仅可见 PUBLIC/CONTACTS） */
    fun getPostsByAuthor(
        currentUserId: String,
        authorId: String,
        limit: Int = 30,
        before: Long? = null,
        beforeId: String? = null
    ): List<PostResponse> {
        return transaction {
            val contactIds = getContactIds(currentUserId)
            val blockedUserIds = getBlockedEitherWayUserIds(currentUserId)
            if (currentUserId != authorId && authorId in blockedUserIds) return@transaction emptyList()
            val baseQuery = (Posts innerJoin Users)
                .selectAll()
                .where { Posts.authorId eq authorId }
            val query = when {
                currentUserId == authorId -> baseQuery
                authorId in contactIds -> baseQuery.andWhere {
                    (Posts.visibility eq "PUBLIC") or (Posts.visibility eq "CONTACTS")
                }
                else -> baseQuery.andWhere { Posts.visibility eq "PUBLIC" }
            }
            if (before != null) {
                query.andWhere {
                    if (beforeId.isNullOrBlank()) {
                        Posts.createdAt less before
                    } else {
                        (Posts.createdAt less before) or
                            ((Posts.createdAt eq before) and (Posts.id less beforeId))
                    }
                }
            }
            val posts = query
                .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 120))
                .toList()
            if (posts.isEmpty()) return@transaction emptyList()
            val visibleIds = posts.map { it[Posts.id] }
            val meta = batchAggregatePostMeta(visibleIds, currentUserId)
            posts.map { it.toPostResponse(currentUserId, meta) }
        }
    }

    fun canView(postId: String, userId: String): Boolean {
        return transaction {
            canViewInTransaction(postId, userId, lockPost = false)
        }
    }

    fun likePost(postId: String, userId: String): Boolean {
        // PG/H2：同事务 catch unique 后继续会 abort 整事务；冲突时外层 re-read
        return try {
            transaction {
                if (!canViewInTransaction(postId, userId, lockPost = true)) return@transaction false
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
                if (!canViewInTransaction(postId, userId, lockPost = true)) return@transaction false
                !PostLikes.selectAll()
                    .where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
                    .empty()
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

    fun addComment(postId: String, authorId: String, content: String, replyToId: String? = null): PostCommentResponse? {
        return transaction {
            if (!canViewInTransaction(postId, authorId, lockPost = true)) return@transaction null
            // 1.76：回复目标必须存在；1.125：且必须属于同一动态（防跨帖引用）
            val parentExists = replyToId.isNullOrBlank() ||
                PostComments.selectAll()
                    .where { (PostComments.id eq replyToId) and (PostComments.postId eq postId) }
                    .limit(1).empty().not()
            if (!parentExists) return@transaction null
            val commentId = "pc_${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            PostComments.insert {
                it[id] = commentId
                it[PostComments.postId] = postId
                it[PostComments.authorId] = authorId
                it[PostComments.content] = content
                it[createdAt] = now
                it[parentId] = replyToId?.takeIf(String::isNotBlank)
            }
            getCommentById(commentId, authorId)
        }
    }

    fun getComments(
        postId: String,
        currentUserId: String,
        limit: Int = 50,
        before: Long? = null,
        beforeId: String? = null
    ): List<PostCommentResponse>? {
        val boundedLimit = limit.coerceIn(1, 100)
        return transaction {
            if (!canViewInTransaction(postId, currentUserId, lockPost = false)) return@transaction null
            val blockedUserIds = getBlockedEitherWayUserIds(currentUserId)
            val result = mutableListOf<PostCommentResponse>()
            var cursorTime = before
            var cursorId = beforeId
            var iterations = 0
            while (result.size < boundedLimit && iterations < MAX_COMMENT_PAGINATION_ITERATIONS) {
                val batchSize = ((boundedLimit - result.size) * 3).coerceAtLeast(boundedLimit)
                val batch = (PostComments innerJoin Users)
                    .selectAll()
                    .where {
                        val time = cursorTime
                        val id = cursorId
                        when {
                            time == null -> PostComments.postId eq postId
                            id.isNullOrBlank() -> (PostComments.postId eq postId) and (PostComments.createdAt less time)
                            else -> (PostComments.postId eq postId) and (
                                (PostComments.createdAt less time) or
                                    ((PostComments.createdAt eq time) and (PostComments.id less id))
                                )
                        }
                    }
                    .orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
                    .limit(batchSize)
                    .toList()
                if (batch.isEmpty()) break
                // 8.38：游标必须按「可见评论的 last」推进，而非 batch.last()——
                // 否则前几批全是被拉黑作者的评论时，可见评论被跳过且提前判「没有更多」
                var lastVisibleTime: Long? = null
                var lastVisibleId: String? = null
                batch.asSequence()
                    .map { it.toCommentResponse(currentUserId) }
                    .filter { it.author.id !in blockedUserIds }
                    .take(boundedLimit - result.size)
                    .forEach { resp ->
                        result.add(resp)
                        lastVisibleTime = resp.createdAt
                        lastVisibleId = resp.id
                    }
                if (lastVisibleId != null && result.size < boundedLimit) {
                    cursorTime = lastVisibleTime
                    cursorId = lastVisibleId
                } else {
                    // 本批无可保留评论或已收集满：推进到批次末尾避免死循环
                    val last = batch.last()
                    cursorTime = last[PostComments.createdAt]
                    cursorId = last[PostComments.id]
                }
                iterations++
            }
            // 1.75：批量填充点赞数据（消除逐条 N+1）
            enrichCommentLikes(result.asReversed(), currentUserId)
        }
    }

    /** 1.93：动态点赞者列表（最新在前；过滤双向拉黑用户）。 */
    fun listPostLikers(postId: String, currentUserId: String, limit: Int = 50): List<UserResponse>? {
        val boundedLimit = limit.coerceIn(1, 100)
        return transaction {
            if (!canViewInTransaction(postId, currentUserId, lockPost = false)) return@transaction null
            val blockedUserIds = getBlockedEitherWayUserIds(currentUserId)
            val result = mutableListOf<UserResponse>()
            var cursorTime: Long? = null
            var cursorUserId: String? = null
            var iterations = 0
            while (result.size < boundedLimit && iterations < MAX_COMMENT_PAGINATION_ITERATIONS) {
                val batchSize = ((boundedLimit - result.size) * 3).coerceAtLeast(boundedLimit)
                val base = (PostLikes innerJoin Users)
                    .selectAll()
                    .where { PostLikes.postId eq postId }
                val time = cursorTime
                val uid = cursorUserId
                val batch = if (time == null || uid == null) {
                    base.orderBy(PostLikes.createdAt to SortOrder.DESC, PostLikes.userId to SortOrder.DESC)
                        .limit(batchSize)
                        .toList()
                } else {
                    base.andWhere {
                        (PostLikes.createdAt less time) or
                            ((PostLikes.createdAt eq time) and (PostLikes.userId less uid))
                    }
                        .orderBy(PostLikes.createdAt to SortOrder.DESC, PostLikes.userId to SortOrder.DESC)
                        .limit(batchSize)
                        .toList()
                }
                if (batch.isEmpty()) break
                result += batch.asSequence()
                    .map { it.toPublicUser() }
                    .filter { it.id !in blockedUserIds }
                    .take(boundedLimit - result.size)
                val last = batch.last()
                cursorTime = last[PostLikes.createdAt]
                cursorUserId = last[PostLikes.userId]
                iterations++
            }
            result
        }
    }

    private fun getCommentById(commentId: String, currentUserId: String): PostCommentResponse? {
        return (PostComments innerJoin Users)
            .selectAll()
            .where { PostComments.id eq commentId }
            .firstOrNull()
            ?.let { enrichCommentLikes(listOf(it.toCommentResponse(currentUserId)), currentUserId).firstOrNull() }
    }

    /** 1.130：公开读取单条评论（路由通知预览用）。 */
    fun getComment(commentId: String, currentUserId: String): PostCommentResponse? =
        getCommentById(commentId, currentUserId)

    private fun ResultRow.toCommentResponse(currentUserId: String): PostCommentResponse {
        val commentId = this[PostComments.id]
        return PostCommentResponse(
            id = commentId,
            postId = this[PostComments.postId],
            author = toPublicUser(),
            content = this[PostComments.content],
            createdAt = this[PostComments.createdAt],
            isMine = this[PostComments.authorId] == currentUserId,
            parentId = this[PostComments.parentId],
            // 点赞数/已赞在 enrichCommentLikes 批量填充（避免 N+1）
            likeCount = 0,
            likedByMe = false
        )
    }

    /** 1.75：批量填充评论点赞数 + 当前用户已赞（两次 SQL 替代逐条 N+1）。 */
    private fun enrichCommentLikes(comments: List<PostCommentResponse>, userId: String): List<PostCommentResponse> {
        if (comments.isEmpty()) return comments
        val ids = comments.map { it.id }
        val likeCounts = CommentLikes
            .select(CommentLikes.commentId, CommentLikes.commentId.count())
            .where { CommentLikes.commentId inList ids }
            .groupBy(CommentLikes.commentId)
            .associate { it[CommentLikes.commentId] to it[CommentLikes.commentId.count()].toInt() }
        val likedByMe = CommentLikes
            .select(CommentLikes.commentId)
            .where { (CommentLikes.commentId inList ids) and (CommentLikes.userId eq userId) }
            .map { it[CommentLikes.commentId] }
            .toSet()
        return comments.map {
            it.copy(likeCount = likeCounts[it.id] ?: 0, likedByMe = it.id in likedByMe)
        }
    }

    /** 1.52：评论点赞数（内联查询，供外层事务内调用；端点响应复用）。 */
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

    /** 1.52：点赞评论（幂等；返回 (新点赞数, 是否新点赞)）。 */
    fun likeComment(commentId: String, userId: String): Pair<Int, Boolean> = transaction {
        val comment = PostComments.selectAll().where { PostComments.id eq commentId }.limit(1).firstOrNull()
            ?: return@transaction (-1 to false)
        // 与 likePost 一致：评论所属动态对当前用户不可见（PRIVATE/CONTACTS/双向拉黑）时禁止点赞，
        // 避免越权交互与“评论是否存在”的探测 oracle
        if (!canViewInTransaction(comment[PostComments.postId], userId, lockPost = false)) {
            return@transaction (-1 to false)
        }
        val alreadyLiked = !CommentLikes.selectAll()
            .where { (CommentLikes.commentId eq commentId) and (CommentLikes.userId eq userId) }
            .limit(1)
            .empty()
        var newLike = false
        if (!alreadyLiked) {
            newLike = true
            runCatching {
                CommentLikes.insert {
                    it[CommentLikes.commentId] = commentId
                    it[CommentLikes.userId] = userId
                    it[CommentLikes.createdAt] = System.currentTimeMillis()
                }
            }
        }
        commentLikeCount(commentId) to newLike
    }

    /** 1.52：取消点赞评论（幂等；返回新点赞数）。 */
    fun unlikeComment(commentId: String, userId: String): Int = transaction {
        val comment = PostComments.selectAll().where { PostComments.id eq commentId }.limit(1).firstOrNull()
            ?: return@transaction -1
        if (!canViewInTransaction(comment[PostComments.postId], userId, lockPost = false)) {
            return@transaction -1
        }
        CommentLikes.deleteWhere {
            (CommentLikes.commentId eq commentId) and (CommentLikes.userId eq userId)
        }
        commentLikeCount(commentId)
    }

    private fun ResultRow.toPublicUser(): UserResponse {
        return UserResponse(
            id = this[Users.id],
            name = this[Users.name],
            email = "",
            avatar = this[Users.avatar],
            status = if (this[Users.showStatus]) this[Users.status] else "",
            isOnline = this[Users.showOnline] && this[Users.isOnline]
        )
    }

    private fun ResultRow.isPostVisibleTo(
        currentUserId: String,
        contactIds: Set<String>,
        blockedUserIds: Set<String>
    ): Boolean {
        val authorId = this[Posts.authorId]
        if (authorId == currentUserId) return true
        // 任一方拉黑：动态不可见（含 PUBLIC）
        if (authorId in blockedUserIds) return false
        return when (normalizeVisibility(this[Posts.visibility])) {
            "PUBLIC" -> true
            "CONTACTS" -> authorId in contactIds
            else -> false
        }
    }

    private fun canViewInTransaction(postId: String, userId: String, lockPost: Boolean): Boolean {
        val contactIds = getContactIds(userId)
        val blockedUserIds = getBlockedEitherWayUserIds(userId)
        val query = Posts.selectAll().where { Posts.id eq postId }
        val row = if (lockPost) query.forUpdate().firstOrNull() else query.firstOrNull()
        return row?.isPostVisibleTo(userId, contactIds, blockedUserIds) == true
    }

    private fun getBlockedEitherWayUserIds(userId: String): Set<String> =
        BlockedUsers
            .select(BlockedUsers.blockerId, BlockedUsers.blockedId)
            .where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .mapTo(hashSetOf()) { row ->
                if (row[BlockedUsers.blockerId] == userId) row[BlockedUsers.blockedId]
                else row[BlockedUsers.blockerId]
            }

    /** CONTACTS = 仅完整 1:1 私聊对方，不含群成员（群邀请陌生人不得读 contacts 动态） */
    private fun getContactIds(userId: String): Set<String> {
        val chatIds = ChatParticipants
            .innerJoin(Chats)
            .select(ChatParticipants.chatId)
            .where { (ChatParticipants.userId eq userId) and (Chats.isGroup eq false) }
            .map { it[ChatParticipants.chatId] }
            .toSet()
        if (chatIds.isEmpty()) return emptySet()
        // 8.30 性能优化 A3：一次 SQL 取回全部 1:1 聊天的成员，替代逐 chat 查询
        val membersByChat = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList chatIds }
            .groupBy({ it[ChatParticipants.chatId] }, { it[ChatParticipants.userId] })
        return membersByChat.mapNotNull { (chatId, members) ->
            if (members.size != 2 || userId !in members) null
            else members.firstOrNull { it != userId }
        }.toSet()
    }

    private fun decodeImageUrls(value: String): List<String> {
        return try {
            json.decodeFromString(imageUrlListSerializer, value)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeVisibility(value: String): String {
        val normalized = value.trim().uppercase()
        return if (normalized in ALLOWED_VISIBILITIES) normalized else "PRIVATE"
    }

    private fun deletePostRow(postId: String): Boolean {
        // 1.126：删除动态时一并清理其全部评论的点赞（孤儿行不再等 6h 兜底）
        val commentIds = PostComments.select(PostComments.id).where { PostComments.postId eq postId }.map { it[PostComments.id] }
        if (commentIds.isNotEmpty()) {
            CommentLikes.deleteWhere { CommentLikes.commentId inList commentIds }
        }
        PostComments.deleteWhere { PostComments.postId eq postId }
        PostLikes.deleteWhere { PostLikes.postId eq postId }
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

    private companion object {
        val ALLOWED_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")
        /** 评论分页迭代上限：游标按可见评论推进后，前几批被拉黑作者也不再「饿死」可见评论。 */
        const val MAX_COMMENT_PAGINATION_ITERATIONS = 20
    }
}
