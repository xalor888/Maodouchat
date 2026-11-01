package com.maodouchat.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextTest {

    private class FakeProvider(initial: SessionContext?) : SessionContextProvider {
        override val contexts = MutableStateFlow(initial)
        override fun isAccessTokenCurrent(context: SessionContext, accessToken: String): Boolean =
            isCurrent(context) && accessToken.isNotBlank()
    }

    @Test
    fun `context equality is by value`() {
        assertEquals(SessionContext("alice", 3L), SessionContext("alice", 3L))
        assertFalse(SessionContext("alice", 3L) == SessionContext("alice", 4L))
        assertFalse(SessionContext("alice", 3L) == SessionContext("bob", 3L))
    }

    @Test
    fun `provider defaults follow the flow value`() {
        val provider = FakeProvider(null)
        assertNull(provider.current())
        assertFalse(provider.isCurrent(SessionContext("alice", 1L)))

        val current = SessionContext("alice", 1L)
        provider.contexts.value = current
        assertEquals(current, provider.current())
        assertTrue(provider.isCurrent(current))
        assertFalse(provider.isCurrent(SessionContext("alice", 2L)))
    }
}
