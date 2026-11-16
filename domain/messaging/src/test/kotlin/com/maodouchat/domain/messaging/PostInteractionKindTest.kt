package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PostInteractionKindTest {

    @Test
    fun `known interactions map one to one`() {
        assertEquals(PostInteractionKind.COMMENT_LIKE, postInteractionKind("COMMENT_LIKE"))
        assertEquals(PostInteractionKind.REPLY, postInteractionKind("REPLY"))
        assertEquals(PostInteractionKind.COMMENT, postInteractionKind("COMMENT"))
    }

    @Test
    fun `unknown interaction falls back to like like the old else branch`() {
        assertEquals(PostInteractionKind.LIKE, postInteractionKind("LIKE"))
        assertEquals(PostInteractionKind.LIKE, postInteractionKind("SHARE"))
        assertEquals(PostInteractionKind.LIKE, postInteractionKind(""))
    }

    @Test
    fun `center suffix follows the old inline branches`() {
        assertEquals("cl", postInteractionCenterSuffix(PostInteractionKind.COMMENT_LIKE, false))
        assertEquals("cl", postInteractionCenterSuffix(PostInteractionKind.COMMENT_LIKE, true))
        assertEquals("c", postInteractionCenterSuffix(PostInteractionKind.REPLY, true))
        assertEquals("c", postInteractionCenterSuffix(PostInteractionKind.COMMENT, true))
        assertEquals("l", postInteractionCenterSuffix(PostInteractionKind.REPLY, false))
        assertEquals("l", postInteractionCenterSuffix(PostInteractionKind.LIKE, false))
    }

    @Test
    fun `center kind follows the old inline branches`() {
        assertEquals("comment_like", postInteractionCenterKind(PostInteractionKind.COMMENT_LIKE, false))
        assertEquals("comment", postInteractionCenterKind(PostInteractionKind.REPLY, true))
        assertEquals("comment", postInteractionCenterKind(PostInteractionKind.COMMENT, true))
        assertEquals("like", postInteractionCenterKind(PostInteractionKind.REPLY, false))
        assertEquals("like", postInteractionCenterKind(PostInteractionKind.LIKE, false))
    }
}
