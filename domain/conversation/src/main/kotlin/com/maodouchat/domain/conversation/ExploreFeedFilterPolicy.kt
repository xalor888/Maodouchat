package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto

// 动态流/朋友圈/作者页过滤谓词：只看自己、只看带图、30 天内公开、按正文搜索。
// 自 ExploreFeedScreen、ExploreMomentsScreen、AuthorProfileScreen 三处收敛；
// 搜索一步复用已在域内的 ExploreSearchPolicy。
object ExploreFeedFilterPolicy {

    // 朋友圈只看 30 天内的公开动态，窗口与原行内算式一致。
    const val THIRTY_DAYS_MS: Long = 30L * 24 * 3600 * 1000

    fun isMine(post: PostDto, currentUserId: String): Boolean =
        post.isMine || post.author.id == currentUserId

    fun hasMedia(post: PostDto): Boolean = post.imageUrls.isNotEmpty()

    fun publicRecent(posts: List<PostDto>, nowMs: Long): List<PostDto> =
        posts.filter { it.visibility == "PUBLIC" && it.createdAt >= nowMs - THIRTY_DAYS_MS }

    fun filterFeed(
        posts: List<PostDto>,
        currentUserId: String,
        showOnlyMine: Boolean,
        showOnlyMedia: Boolean,
        query: String,
    ): List<PostDto> {
        var base = posts
        if (showOnlyMine) base = base.filter { isMine(it, currentUserId) }
        if (showOnlyMedia) base = base.filter { hasMedia(it) }
        return ExploreSearchPolicy.filter(base, query, { it.content }, { it.author.name }, { it.author.id })
    }

    fun filterMoments(
        posts: List<PostDto>,
        nowMs: Long,
        showOnlyMedia: Boolean,
        query: String,
    ): List<PostDto> {
        var base = publicRecent(posts, nowMs)
        if (showOnlyMedia) base = base.filter { hasMedia(it) }
        return ExploreSearchPolicy.filter(base, query, { it.content }, { it.author.name }, { it.author.id })
    }

    fun filterByContent(posts: List<PostDto>, query: String): List<PostDto> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return posts
        return posts.filter { it.content.contains(trimmed, ignoreCase = true) }
    }
}
