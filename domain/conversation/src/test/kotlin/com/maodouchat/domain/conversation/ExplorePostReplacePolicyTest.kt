package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorePostReplacePolicyTest {

    private fun post(id: String, content: String) =
        PostDto(
            id = id,
            author = UserDto(id = "u1", name = "A"),
            content = content,
            createdAt = 1L
        )

    @Test
    fun `replaces matching post and keeps order and others`() {
        val posts = listOf(post("p1", "a"), post("p2", "b"), post("p3", "c"))
        val updated = post("p2", "B")
        val result = ExplorePostReplacePolicy.replace(posts, "p2", updated)
        assertEquals(listOf(post("p1", "a"), updated, post("p3", "c")), result)
    }

    @Test
    fun `replaces first post`() {
        val posts = listOf(post("p1", "a"), post("p2", "b"))
        val result = ExplorePostReplacePolicy.replace(posts, "p1", post("p1", "A"))
        assertEquals(listOf(post("p1", "A"), post("p2", "b")), result)
    }

    @Test
    fun `replaces last post`() {
        val posts = listOf(post("p1", "a"), post("p2", "b"))
        val result = ExplorePostReplacePolicy.replace(posts, "p2", post("p2", "B"))
        assertEquals(listOf(post("p1", "a"), post("p2", "B")), result)
    }

    @Test
    fun `unknown id returns list unchanged`() {
        val posts = listOf(post("p1", "a"), post("p2", "b"))
        val result = ExplorePostReplacePolicy.replace(posts, "p9", post("p9", "x"))
        assertEquals(posts, result)
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(
            emptyList<PostDto>(),
            ExplorePostReplacePolicy.replace(emptyList<PostDto>(), "p1", post("p1", "x"))
        )
    }
}
