package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test

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
