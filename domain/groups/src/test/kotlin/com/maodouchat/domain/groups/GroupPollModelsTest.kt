package com.maodouchat.domain.groups

import kotlin.test.assertEquals
import kotlin.test.Test

class GroupPollModelsTest {

    @Test
    fun `poll list item keeps fields and copy semantics`() {
        val poll = PollListItem(
            id = "p1", question = "去哪？", options = listOf("A", "B"),
            multi = false, anonymous = true, closed = false,
            counts = listOf(2, 1), totalVoters = 3, myVotes = listOf(0)
        )
        assertEquals("去哪？", poll.question)
        assertEquals(listOf(2, 1), poll.counts)
        assertEquals(true, poll.copy(closed = true).closed)
    }
}
