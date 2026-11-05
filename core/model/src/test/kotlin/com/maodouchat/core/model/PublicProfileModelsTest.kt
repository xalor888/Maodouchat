package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PublicProfileModelsTest {

    @Test
    fun `public profile data keeps defaults`() {
        val data = PublicProfileData(id = "u1", name = "Alice")
        assertEquals("u1", data.id)
        assertEquals("", data.status)
        assertFalse(data.isOnline)
        assertEquals("Bob", data.copy(name = "Bob").name)
    }
}
