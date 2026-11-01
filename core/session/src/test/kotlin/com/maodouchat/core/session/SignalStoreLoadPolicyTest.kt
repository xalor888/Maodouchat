package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalStoreLoadPolicyTest {

    @Test
    fun blankRowsAreNotLoadable() {
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow(null, "YWJj"))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "   "))
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "YWJj"))
    }

    @Test
    fun onlyNonEmptyDataIsPersistable() {
        assertFalse(SignalStoreLoadPolicy.isPersistable("session:u1:1", null))
        assertFalse(SignalStoreLoadPolicy.isPersistable("session:u1:1", byteArrayOf()))
        assertTrue(SignalStoreLoadPolicy.isPersistable("session:u1:1", byteArrayOf(1)))
    }

    @Test
    fun logicalKeyStripsPrefixOnly() {
        assertEquals("u1:1", SignalStoreLoadPolicy.logicalKeyOrNull("session:u1:1", "session:"))
        assertEquals("other:u1:1", SignalStoreLoadPolicy.logicalKeyOrNull("other:u1:1", "session:"))
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("session:", "session:"))
    }
}
