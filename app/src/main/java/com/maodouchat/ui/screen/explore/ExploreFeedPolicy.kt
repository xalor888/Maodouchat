package com.maodouchat.ui.screen.explore

import com.maodouchat.network.PostDto

/**
 * W3-02: pure optimistic feed mutations with rollback-friendly snapshots.
 */
object ExploreFeedPolicy {
    data class Cursor(val createdAt: Long, val postId: String)

    data class LikeToggle(
        val optimistic: PostDto,
        val previous: PostDto,
        val willLike: Boolean
    )

    fun toggleLike(post: PostDto): LikeToggle {
        val willLike = !post.likedByMe
        val nextCount = (post.likeCount + if (willLike) 1 else -1).coerceAtLeast(0)
        return LikeToggle(
            optimistic = post.copy(likedByMe = willLike, likeCount = nextCount),
            previous = post,
            willLike = willLike
        )
    }

    fun applyServerPost(posts: List<PostDto>, server: PostDto): List<PostDto> =
        posts.map { if (it.id == server.id) server else it }

    fun rollbackPost(posts: List<PostDto>, previous: PostDto): List<PostDto> =
        posts.map { if (it.id == previous.id) previous else it }

    fun removePost(posts: List<PostDto>, postId: String): List<PostDto> =
        posts.filterNot { it.id == postId }

    fun restorePost(posts: List<PostDto>, post: PostDto, originalIndex: Int): List<PostDto> {
        if (posts.any { it.id == post.id }) return posts
        val index = originalIndex.coerceIn(0, posts.size)
        return posts.toMutableList().apply { add(index, post) }
    }

    fun incrementCommentCount(posts: List<PostDto>, postId: String): List<PostDto> =
        posts.map { post ->
            if (post.id == postId) post.copy(commentCount = post.commentCount + 1) else post
        }

    /** 1.117：删除评论后递减评论计数（不低于 0）。 */
    fun decrementCommentCount(posts: List<PostDto>, postId: String): List<PostDto> =
        posts.map { post ->
            if (post.id == postId && post.commentCount > 0) post.copy(commentCount = post.commentCount - 1) else post
        }

    /** Fullscreen gallery: clamp index when urls change mid-session. */
    fun clampImageIndex(index: Int, size: Int): Int {
        if (size <= 0) return 0
        return index.coerceIn(0, size - 1)
    }

    /** 1.139：大数紧凑显示（1.2K / 1.3万），0 保持 "0"。 */
    fun formatCount(n: Int): String {
        if (n <= 0) return "0"
        return when {
            n < 1000 -> n.toString()
            n < 10_000 -> compactUnit(n / 1000.0, "K")
            else -> compactUnit(n / 10_000.0, "万")
        }
    }

    private fun compactUnit(value: Double, suffix: String): String {
        val v = value.roundToOneDecimal()
        val number = if (v.endsWith(".0")) v.dropLast(2) else v
        return number + suffix
    }

    private fun Double.roundToOneDecimal(): String =
        String.format(java.util.Locale.US, "%.1f", this)

    /**
     * Pagination entry guard (before setting isLoadingMore).
     * Prevents stuck "load more" spinners and duplicate page fetches.
     */
    fun canStartLoadMore(
        isLoading: Boolean,
        isLoadingMore: Boolean,
        hasMore: Boolean,
        postCount: Int
    ): Boolean = !isLoading && !isLoadingMore && hasMore && postCount > 0

    fun oldestCursor(posts: List<PostDto>): Cursor? = posts
        .minWithOrNull(compareBy<PostDto> { it.createdAt }.thenBy { it.id })
        ?.let { Cursor(createdAt = it.createdAt, postId = it.id) }

    /**
     * Session check before flipping isLoadingMore=true.
     * Missing token must clear loading and surface login — never early-return mid-flag.
     */
    fun missingSessionForLoadMore(token: String?): Boolean = token.isNullOrBlank()
}
