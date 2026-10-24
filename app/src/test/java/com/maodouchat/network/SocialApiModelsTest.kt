package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createPostRequestDefaultsUseAccountVisibility() {
        val request = CreatePostRequest(content = "hello")
        assertTrue(request.useDefaultVisibility)
        assertNull(request.visibility)
        val decoded = json.decodeFromString<CreatePostRequest>("""{"content":"hello"}""")
        assertTrue(decoded.useDefaultVisibility)
        assertEquals("hello", decoded.content)
    }

    @Test
    fun postDtoFillsLikeDefaultsWhenFeedOmitsThem() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p1","author":{"id":"u1","name":"Alice"},"content":"hi","createdAt":1}"""
        )
        assertEquals("PUBLIC", decoded.visibility)
        assertEquals(0, decoded.likeCount)
        assertEquals(0, decoded.commentCount)
        assertFalse(decoded.likedByMe)
        assertFalse(decoded.isMine)
        assertEquals(emptyList<String>(), decoded.imageUrls)
    }

    @Test
    fun postDtoRoundTripsLikedState() {
        val post = PostDto(
            id = "p2",
            author = UserDto(id = "u2", name = "Bob"),
            content = "public",
            visibility = "CONTACTS",
            createdAt = 9L,
            likeCount = 2,
            commentCount = 1,
            likedByMe = true,
            isMine = false
        )
        assertEquals(post, json.decodeFromString<PostDto>(json.encodeToString(post)))
    }

    @Test
    fun postCommentDefaultsNoParentAndNoLikes() {
        val decoded = json.decodeFromString<PostCommentDto>(
            """{"id":"c1","postId":"p1","author":{"id":"u1","name":"A"},"content":"nice","createdAt":3}"""
        )
        assertNull(decoded.parentId)
        assertEquals(0, decoded.likeCount)
        assertFalse(decoded.likedByMe)
        assertFalse(decoded.isMine)
    }

    @Test
    fun postLikersDefaultsEmptyList() {
        val decoded = json.decodeFromString<PostLikersResponse>("""{"postId":"p1"}""")
        assertEquals(emptyList<UserDto>(), decoded.likers)
        val filled = json.decodeFromString<PostLikersResponse>(
            """{"postId":"p1","likers":[{"id":"u2","name":"B"}]}"""
        )
        assertEquals(1, filled.likers.size)
        assertEquals("B", filled.likers[0].name)
    }

    @Test
    fun createCommentRequestReplyToIdRoundTrips() {
        val request = CreateCommentRequest(content = "re", replyToId = "c9")
        assertEquals(request, json.decodeFromString<CreateCommentRequest>(json.encodeToString(request)))
        val topLevel = json.decodeFromString<CreateCommentRequest>("""{"content":"top"}""")
        assertNull(topLevel.replyToId)
    }

    @Test
    fun nearbyLocationStatusDefaultsNotExpired() {
        val decoded = json.decodeFromString<NearbyLocationStatusResponse>("""{"sharing":true}""")
        assertTrue(decoded.sharing)
        assertEquals(0L, decoded.expiresAt)
    }

    @Test
    fun createPostRequestExplicitVisibilityDisablesDefault() {
        val request = CreatePostRequest(content = "x", visibility = "CONTACTS", useDefaultVisibility = false)
        val decoded = json.decodeFromString<CreatePostRequest>(json.encodeToString(request))
        assertEquals("CONTACTS", decoded.visibility)
        assertFalse(decoded.useDefaultVisibility)
        assertEquals(listOf("a.jpg"), json.decodeFromString<CreatePostRequest>(
            """{"content":"y","imageUrls":["a.jpg"]}"""
        ).imageUrls)
    }

    @Test
    fun postDtoEditedAtDefaultsNullAndEmptyImages() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p3","author":{"id":"u1","name":"A"},"content":"hi","createdAt":2}"""
        )
        assertNull(decoded.editedAt)
        assertEquals(emptyList<String>(), decoded.imageUrls)
    }

    @Test
    fun postCommentDtoReplyParentIdRoundTrips() {
        val comment = PostCommentDto(
            id = "c2",
            postId = "p1",
            author = UserDto(id = "u1", name = "A"),
            content = "reply",
            createdAt = 4L,
            parentId = "c1",
            likeCount = 1,
            likedByMe = true
        )
        assertEquals(comment, json.decodeFromString<PostCommentDto>(json.encodeToString(comment)))
    }

    @Test
    fun postLikersResponseRoundTripsMultipleLikers() {
        val response = PostLikersResponse(
            postId = "p1",
            likers = listOf(UserDto(id = "u2", name = "B"), UserDto(id = "u3", name = "C"))
        )
        val decoded = json.decodeFromString<PostLikersResponse>(json.encodeToString(response))
        assertEquals(2, decoded.likers.size)
        assertEquals("C", decoded.likers[1].name)
    }

    @Test
    fun createPostRequestEmptyContentRoundTrips() {
        val decoded = json.decodeFromString<CreatePostRequest>("{}")
        assertEquals("", decoded.content)
        assertTrue(decoded.useDefaultVisibility)
        assertNull(decoded.visibility)
    }

    @Test
    fun postDtoIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p4","author":{"id":"u1","name":"A"},"content":"hi","createdAt":2,"views":9}"""
        )
        assertEquals("p4", decoded.id)
        assertEquals(0, decoded.likeCount)
    }
}
