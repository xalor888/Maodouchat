package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LinkPreviewRepositoryModelsTest {

    @Test
    fun `in flight lock starts with zero users`() {
        val lock = InFlightLock()
        assertEquals(0, lock.users)
        assertNotNull(lock.mutex)
        lock.users = 3
        assertEquals(3, lock.users)
    }
}
