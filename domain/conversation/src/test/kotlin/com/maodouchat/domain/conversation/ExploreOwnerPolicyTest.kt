package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreOwnerPolicyTest {

    @Test
    fun `same non-blank id is the current owner`() {
        assertEquals(true, ExploreOwnerPolicy.isCurrentOwner("u1", "u1"))
    }

    @Test
    fun `different id is not the current owner`() {
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner("u1", "u2"))
    }

    @Test
    fun `blank expected id is not the owner even when logged out`() {
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner("", ""))
    }

    @Test
    fun `blank expected id is not the owner when logged in`() {
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner("u1", ""))
    }

    @Test
    fun `blank current id never matches a non-blank expected id`() {
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner("", "u1"))
    }

    @Test
    fun `whitespace-only expected id is not the owner`() {
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner(" ", " "))
    }

    @Test
    fun `null current id is never the owner`() {
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner(null, "u1"))
        assertEquals(false, ExploreOwnerPolicy.isCurrentOwner(null, ""))
    }
}
