package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocalAiCompletion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCompletionResultPolicyTest {

    @Test
    fun `text result is trimmed`() {
        assertEquals("hi", AgentCompletionResultPolicy.toResult(LocalAiCompletion.Text("  hi  ")).getOrThrow())
    }

    @Test
    fun `tools result uses content and trims it`() {
        val result = AgentCompletionResultPolicy.toResult(
            LocalAiCompletion.Tools(emptyList(), "  done  ")
        )
        assertEquals("done", result.getOrThrow())
    }

    @Test
    fun `error result fails with the model message`() {
        val result = AgentCompletionResultPolicy.toResult(LocalAiCompletion.Error("boom"))
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `blank text error`() {
        assertEquals("Error: text required", AgentCompletionResultPolicy.blankTextError())
    }

    @Test
    fun `rewrite failed fallback`() {
        assertEquals("rewrite failed", AgentCompletionResultPolicy.rewriteFailedFallback())
    }
}
