package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreShareTextPolicyTest {

    private fun post(
        content: String = "Hello",
        imageUrls: List<String> = emptyList(),
        username: String? = "alice"
    ) = PostDto(
        id = "p1",
        author = UserDto(id = "u1", name = "Alice", username = username),
        content = content,
        imageUrls = imageUrls,
        createdAt = 1L
    )

    private fun share(
        content: String = "Hello",
        imageUrls: List<String> = emptyList(),
        username: String? = "alice",
        baseUrl: String = "https://example.com"
    ) = ExploreShareTextPolicy.buildShareText(
        post(content, imageUrls, username), baseUrl
    )

    @Test
    fun `content only is quoted`() {
        assertEquals("\"Hello\"", share(username = null))
    }

    @Test
    fun `images append marker on new line`() {
        assertEquals("\"Hello\"\n[图片]", share(imageUrls = listOf("a.jpg"),
            username = null))
    }

    @Test
    fun `author link appends on new line`() {
        assertEquals(
            "\"Hello\"\nhttps://example.com/u/alice", share()
        )
    }

    @Test
    fun `blank content without images or author falls back`() {
        assertEquals("动态", share(content = "", username = null))
    }

    @Test
    fun `blank content with images yields marker only`() {
        assertEquals("[图片]", share(content = "  ", imageUrls = listOf("a.jpg"),
            username = null))
    }

    @Test
    fun `blank content with author yields link only`() {
        assertEquals(
            "https://example.com/u/alice",
            share(content = "", username = "alice")
        )
    }

    @Test
    fun `trailing slash in base url is trimmed`() {
        assertEquals(
            "\"Hello\"\nhttps://example.com/u/alice",
            share(baseUrl = "https://example.com/")
        )
    }
}
