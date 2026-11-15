package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostCommentDto
import com.maodouchat.core.model.UserDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreCommentReplacePolicyTest {

    private fun comment(id: String, content: String) =
        PostCommentDto(
            id = id,
            postId = "p1",
            author = UserDto(id = "u1", name = "A"),
            content = content,
            createdAt = 1L
        )

    @Test
    fun `replaces matching comment and keeps order and others`() {
        val comments = listOf(comment("c1", "a"), comment("c2", "b"), comment("c3", "c"))
        val updated = comment("c2", "B")
        val result = ExploreCommentReplacePolicy.replace(comments, "c2", updated)
        assertEquals(listOf(comment("c1", "a"), updated, comment("c3", "c")), result)
    }

    @Test
    fun `replaces first comment`() {
        val comments = listOf(comment("c1", "a"), comment("c2", "b"))
        val result = ExploreCommentReplacePolicy.replace(comments, "c1", comment("c1", "A"))
        assertEquals(listOf(comment("c1", "A"), comment("c2", "b")), result)
    }

    @Test
    fun `replaces last comment`() {
        val comments = listOf(comment("c1", "a"), comment("c2", "b"))
        val result = ExploreCommentReplacePolicy.replace(comments, "c2", comment("c2", "B"))
        assertEquals(listOf(comment("c1", "a"), comment("c2", "B")), result)
    }

    @Test
    fun `unknown id returns list unchanged`() {
        val comments = listOf(comment("c1", "a"), comment("c2", "b"))
        val result = ExploreCommentReplacePolicy.replace(comments, "c9", comment("c9", "x"))
        assertEquals(comments, result)
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(
            emptyList<PostCommentDto>(),
            ExploreCommentReplacePolicy.replace(emptyList<PostCommentDto>(), "c1", comment("c1", "x"))
        )
    }
}
