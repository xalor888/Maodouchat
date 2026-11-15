package com.maodouchat.domain.contacts

import com.maodouchat.core.model.NearbyPerson
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbySearchPolicyTest {

    private fun person(id: String, name: String, status: String, nickname: String? = null) =
        NearbyPerson(User(id = id, name = name, status = status, nickname = nickname), distanceMeters = 100, locationUpdatedAt = 0)

    private val people = listOf(
        person("u1", "Alice", "out for a walk"),
        person("u2", "Bob", "at school"),
    )

    @Test
    fun `filter returns all on blank query`() {
        assertEquals(people, NearbySearchPolicy.filter(people, "  "))
    }

    @Test
    fun `matches displayName from nickname`() {
        val item = person("u9", "Zed", "busy", nickname = "Ally")
        assertTrue(NearbySearchPolicy.matches(item, "ally"))
        assertFalse(NearbySearchPolicy.matches(item, "carol"))
    }

    @Test
    fun `matches name`() {
        assertTrue(NearbySearchPolicy.matches(people[0], "alice"))
        assertFalse(NearbySearchPolicy.matches(people[1], "alice"))
    }

    @Test
    fun `matches id`() {
        assertTrue(NearbySearchPolicy.matches(people[1], "U2"))
        assertFalse(NearbySearchPolicy.matches(people[0], "u2"))
    }

    @Test
    fun `matches status`() {
        assertTrue(NearbySearchPolicy.matches(people[1], "school"))
        assertFalse(NearbySearchPolicy.matches(people[0], "school"))
    }

    @Test
    fun `filter returns empty when nothing matches`() {
        assertTrue(NearbySearchPolicy.filter(people, "zzz").isEmpty())
    }

    @Test
    fun `filter is case-insensitive and trims`() {
        assertEquals(listOf(people[1]), NearbySearchPolicy.filter(people, " BOB "))
    }
}
