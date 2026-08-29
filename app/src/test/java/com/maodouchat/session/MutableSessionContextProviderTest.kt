package com.maodouchat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutableSessionContextProviderTest {
    @Test
    fun sameOwnerReusesGenerationUntilInvalidated() {
        val provider = MutableSessionContextProvider()

        val first = provider.activate("alice")
        val repeated = provider.activate("alice")

        assertEquals(first, repeated)
        assertEquals(first, provider.contexts.value)
    }

    @Test
    fun accountChangeAndTerminationAdvanceGeneration() {
        val provider = MutableSessionContextProvider()
        val alice = provider.activate("alice")
        val bob = provider.activate("bob")

        assertTrue(bob.generation > alice.generation)
        assertNull(provider.invalidate(expectedOwnerUserId = "alice"))
        assertEquals(bob, provider.contexts.value)

        assertEquals(bob, provider.invalidate(expectedOwnerUserId = "bob"))
        assertNull(provider.contexts.value)

        val nextBob = provider.activate("bob")
        assertTrue(nextBob.generation > bob.generation)
        assertFalse(nextBob == bob)
    }
}
