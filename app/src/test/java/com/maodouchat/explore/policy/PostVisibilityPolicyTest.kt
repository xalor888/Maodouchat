package com.maodouchat.explore.policy

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostVisibilityPolicyTest {

    private fun createPost(
        id: String,
        authorId: String,
        visibility: String,
        isMine: Boolean = false
    ) = PostDto(
        id = id,
        author = UserDto(id = authorId, name = "Author $authorId"),
        content = "Post $id content",
        visibility = visibility,
        createdAt = 1000L,
        isMine = isMine
    )

    @Test
    fun publicPost_visibleToAnyNonBlockedUser() {
        val post = createPost("p1", "author1", "PUBLIC")
        assertTrue(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer1", isFriend = false))
    }

    @Test
    fun publicPost_hiddenWhenAuthorIsBlocked() {
        val post = createPost("p1", "author1", "PUBLIC")
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer1", isAuthorBlocked = true))
    }

    @Test
    fun publicPost_hiddenWhenReported() {
        val post = createPost("p1", "author1", "PUBLIC")
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer1", isReported = true))
    }

    @Test
    fun contactsPost_visibleToFriend_hiddenFromStranger() {
        val post = createPost("p2", "author1", "CONTACTS")
        assertTrue(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer1", isFriend = true))
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer2", isFriend = false))
    }

    @Test
    fun friendsAlias_treatedAsContacts() {
        val post = createPost("p2-alias", "author1", "FRIENDS")
        assertTrue(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer1", isFriend = true))
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer2", isFriend = false))
    }

    @Test
    fun privatePost_onlyVisibleToAuthor() {
        val post = createPost("p3", "author1", "PRIVATE")
        // Author viewing own post
        assertTrue(PostVisibilityPolicy.isVisible(post, viewerUserId = "author1", isFriend = false))
        // Stranger or friend viewing private post
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "friend1", isFriend = true))
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "stranger", isFriend = false))
    }

    @Test
    fun authorAlwaysSeesOwnPost_evenIfPrivateOrContacts() {
        val privatePost = createPost("p4", "me", "PRIVATE", isMine = true)
        val contactsPost = createPost("p5", "me", "CONTACTS", isMine = true)
        val publicPost = createPost("p6", "me", "PUBLIC", isMine = true)

        assertTrue(PostVisibilityPolicy.isVisible(privatePost, viewerUserId = "me"))
        assertTrue(PostVisibilityPolicy.isVisible(contactsPost, viewerUserId = "me"))
        assertTrue(PostVisibilityPolicy.isVisible(publicPost, viewerUserId = "me"))
    }

    @Test
    fun unknownVisibility_failsClosedToPrivate() {
        val post = createPost("p7", "author1", "SECRET_INVALID")
        assertFalse(PostVisibilityPolicy.isVisible(post, viewerUserId = "viewer1", isFriend = true))
        assertTrue(PostVisibilityPolicy.isVisible(post, viewerUserId = "author1"))
    }

    @Test
    fun filterVisiblePosts_appliesAllConditions() {
        val posts = listOf(
            createPost("p1", "author1", "PUBLIC"),
            createPost("p2", "blocked_author", "PUBLIC"),
            createPost("p3", "author2", "CONTACTS"),
            createPost("p4", "author3", "CONTACTS"),
            createPost("p5", "author4", "PRIVATE"),
            createPost("p6", "me", "PRIVATE", isMine = true),
            createPost("p7", "reported_author", "PUBLIC")
        )

        val visible = PostVisibilityPolicy.filterVisiblePosts(
            posts = posts,
            viewerUserId = "me",
            friendUserIds = setOf("author2"), // author2 is friend, author3 is not
            blockedUserIds = setOf("blocked_author"),
            reportedPostIds = setOf("p7")
        )

        val visibleIds = visible.map { it.id }
        assertEquals(listOf("p1", "p3", "p6"), visibleIds)
    }
}
