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
}
