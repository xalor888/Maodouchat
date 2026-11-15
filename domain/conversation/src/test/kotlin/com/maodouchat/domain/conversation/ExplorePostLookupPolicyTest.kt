package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplorePostLookupPolicyTest {

    private fun post(id: String, content: String = "hi") =
        PostDto(
            id = id,
            author = UserDto(id = "u1", name = "A"),
            content = content,
            createdAt = 1L
        )

    @Test
    fun `finds middle post`() {
        val posts = listOf(post("p1"), post("p2"), post("p3"))
        assertEquals(post("p2"), ExplorePostLookupPolicy.findById(posts, "p2"))
    }

    @Test
    fun `finds first post`() {
        val posts = listOf(post("p1"), post("p2"))
        assertEquals(post("p1"), ExplorePostLookupPolicy.findById(posts, "p1"))
    }

    @Test
    fun `finds last post`() {
        val posts = listOf(post("p1"), post("p2"))
        assertEquals(post("p2"), ExplorePostLookupPolicy.findById(posts, "p2"))
    }

    @Test
    fun `unknown id returns null`() {
        val posts = listOf(post("p1"), post("p2"))
        assertNull(ExplorePostLookupPolicy.findById(posts, "p9"))
    }

    @Test
    fun `empty list returns null`() {
        assertNull(ExplorePostLookupPolicy.findById(emptyList<PostDto>(), "p1"))
    }
}
