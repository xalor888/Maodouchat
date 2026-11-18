package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkQualityTest {

    @Test
    fun `network qualities keep declaration order`() {
        assertEquals(
            listOf(
                NetworkQuality.GOOD,
                NetworkQuality.FAIR,
                NetworkQuality.POOR,
                NetworkQuality.UNKNOWN,
            ),
            NetworkQuality.entries,
        )
    }

    @Test
    fun `valueOf round-trips every quality`() {
        NetworkQuality.entries.forEach {
            assertEquals(it, NetworkQuality.valueOf(it.name))
        }
    }
}
