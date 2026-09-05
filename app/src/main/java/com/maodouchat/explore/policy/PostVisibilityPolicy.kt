package com.maodouchat.explore.policy

import com.maodouchat.network.PostDto

enum class PostVisibilityTier {
    PUBLIC,
    CONTACTS,
    PRIVATE;

    companion object {
        fun fromString(value: String?): PostVisibilityTier = when (value?.trim()?.uppercase()) {
            "PUBLIC" -> PUBLIC
            "CONTACTS", "FRIENDS" -> CONTACTS
            "PRIVATE" -> PRIVATE
            else -> PRIVATE // Fail-closed to PRIVATE
        }
    }
}

typealias PostVisibility = PostVisibilityTier

object PostVisibilityPolicy {

    fun formatForApi(tier: PostVisibilityTier): String = when (tier) {
        PostVisibilityTier.PUBLIC -> "PUBLIC"
        PostVisibilityTier.CONTACTS -> "CONTACTS"
        PostVisibilityTier.PRIVATE -> "PRIVATE"
    }

    /**
     * Determines whether a post is visible to the given viewer.
     *
     * Rules:
     * 1. If author is blocked or post is reported/flagged: NOT visible.
     * 2. If viewer is the author: always visible to self.
     * 3. If PRIVATE: only visible to author.
     * 4. If CONTACTS/FRIENDS: visible to author and confirmed contacts/friends.
     * 5. If PUBLIC: visible to all non-blocked users.
     */
    fun isVisible(
        post: PostDto,
        viewerUserId: String,
        isFriend: Boolean = false,
        isAuthorBlocked: Boolean = false,
        isReported: Boolean = false
    ): Boolean {
        if (isAuthorBlocked || isReported) {
            return false
        }

        val isAuthor = viewerUserId.isNotBlank() && (post.author.id == viewerUserId || post.isMine)
        if (isAuthor) {
            return true
        }

        return when (PostVisibilityTier.fromString(post.visibility)) {
            PostVisibilityTier.PUBLIC -> true
            PostVisibilityTier.CONTACTS -> isFriend
            PostVisibilityTier.PRIVATE -> false
        }
    }

    /**
     * Filters a collection of posts according to relationships, blocks, and reports.
     */
    fun filterVisiblePosts(
        posts: List<PostDto>,
        viewerUserId: String,
        friendUserIds: Set<String> = emptySet(),
        blockedUserIds: Set<String> = emptySet(),
        reportedPostIds: Set<String> = emptySet()
    ): List<PostDto> {
        if (posts.isEmpty()) return emptyList()
        return posts.filter { post ->
            val isFriend = friendUserIds.contains(post.author.id)
            val isBlocked = blockedUserIds.contains(post.author.id)
            val isReported = reportedPostIds.contains(post.id)
            isVisible(
                post = post,
                viewerUserId = viewerUserId,
                isFriend = isFriend,
                isAuthorBlocked = isBlocked,
                isReported = isReported
            )
        }
    }
}
