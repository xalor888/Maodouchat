package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
