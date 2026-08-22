package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalStoreLoadPolicyTest {

    @Test
    fun dropsBlankOrNullRows() {
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow(null, "YWJj"))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", null))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("", "YWJj"))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "   "))
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "YWJj"))
    }

    @Test
    fun persistableRequiresNonEmptyPayload() {
        assertFalse(SignalStoreLoadPolicy.isPersistable(null, byteArrayOf(1)))
        assertFalse(SignalStoreLoadPolicy.isPersistable("prekey:1", null))
        assertFalse(SignalStoreLoadPolicy.isPersistable("prekey:1", byteArrayOf()))
        assertTrue(SignalStoreLoadPolicy.isPersistable("prekey:1", byteArrayOf(1)))
    }

    @Test
    fun logicalKeyStripsAccountPrefix() {
        val prefix = "user:alice:"
        assertEquals("session:bob:1", SignalStoreLoadPolicy.logicalKeyOrNull("user:alice:session:bob:1", prefix))
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("user:alice:", prefix))
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("", prefix))
        assertEquals("orphan", SignalStoreLoadPolicy.logicalKeyOrNull("orphan", prefix))
    }
}
