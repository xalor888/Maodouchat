package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMutationModelsTest {

    @Test
    fun `mutation session changed is an illegal state`() {
        val error = MutationSessionChangedException()
        assertTrue(error is IllegalStateException)
        assertEquals("Session changed during post mutation", error.message)
    }
}
