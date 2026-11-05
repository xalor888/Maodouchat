package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ConversationReactionCoordinatorModelsTest {

    @Test
    fun `reaction lock starts with zero users`() {
        val lock = ReactionLock()
        assertEquals(0, lock.users)
        assertNotNull(lock.mutex)
        lock.users = 2
        assertEquals(2, lock.users)
    }
}
