package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationCenterExtraJsonPolicyTest {
    @Test
    fun emptyMapEncodesToEmptyObject() {
        assertEquals("{}", NotificationCenterExtraJsonPolicy.encode(emptyMap()))
    }

    @Test
    fun roundTripPreservesEntriesIncludingUnicode() {
        val extra = mapOf("k1" to "v1", "中文" to "你好，世界", "q" to "a\"b")
        assertEquals(extra, NotificationCenterExtraJsonPolicy.decode(
            NotificationCenterExtraJsonPolicy.encode(extra)))
    }

    @Test
    fun decodeInvalidJsonReturnsEmptyMap() {
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode("not json"))
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode(""))
    }

    @Test
    fun decodeKeepsValuesAsStrings() {
        assertEquals(mapOf("a" to "1"), NotificationCenterExtraJsonPolicy.decode("{\"a\":1}"))
    }

    @Test
    fun displayCountClampsToOne() {
        assertEquals(1, NotificationCenterExtraJsonPolicy.displayCount(0))
        assertEquals(1, NotificationCenterExtraJsonPolicy.displayCount(-3))
        assertEquals(5, NotificationCenterExtraJsonPolicy.displayCount(5))
    }

    @Test
    fun decodeEmptyObjectReturnsEmptyMap() {
        assertTrue(NotificationCenterExtraJsonPolicy.decode("{}").isEmpty())
    }
}
