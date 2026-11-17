package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentPostRowPolicyTest {

    @Test
    fun `postListRow joins id author counts and preview with tabs`() {
        assertEquals(
            "p1\tAlice\tlikes=5\tcomments=2\tHello world",
            AgentPostRowPolicy.postListRow("p1", "Alice", 5, 2, "Hello world"),
        )
    }

    @Test
    fun `postDetail puts header on first line and content on second`() {
        assertEquals(
            "id=p1\tauthor=u1/Alice\tlikes=5\tcomments=2\tmine=true\nHello world",
            AgentPostRowPolicy.postDetail("p1", "u1", "Alice", 5, 2, true, "Hello world"),
        )
    }

    @Test
    fun `commentRow joins id author and content with tabs`() {
        assertEquals(
            "c9\tBob\tNice!",
            AgentPostRowPolicy.commentRow("c9", "Bob", "Nice!"),
        )
    }
}
