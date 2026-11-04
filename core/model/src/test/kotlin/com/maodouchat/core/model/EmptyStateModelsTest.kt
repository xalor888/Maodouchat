package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyStateModelsTest {

    @Test
    fun `entries cover every placeholder kind`() {
        assertEquals(
            listOf(
                EmptyStateType.CHAT_LIST,
                EmptyStateType.SEARCH,
                EmptyStateType.CONTACTS,
                EmptyStateType.MOMENTS,
                EmptyStateType.NETWORK_ERROR,
                EmptyStateType.GENERIC,
            ),
            EmptyStateType.entries.toList(),
        )
    }

    @Test
    fun `names round-trip`() {
        assertEquals(EmptyStateType.SEARCH, EmptyStateType.valueOf("SEARCH"))
        assertEquals(EmptyStateType.GENERIC, EmptyStateType.valueOf("GENERIC"))
    }
}
