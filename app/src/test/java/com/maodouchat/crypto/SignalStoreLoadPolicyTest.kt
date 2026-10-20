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

    @Test
    fun loadRowAcceptsPaddedButNonBlankFields() {
        // keyType 先 trim 再判定：两端空格不影响加载
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("  session:u1:1  ", "YWJj"))
        // keyData 只要不是纯空白就加载，不做 Base64 合法性判定
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "  YWJj  "))
    }

    @Test
    fun loadRowDropsNullAndBlankFields() {
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow(null, null))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("  ", "YWJj"))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", ""))
    }

    @Test
    fun persistableRejectsBlankKeyTypeAndAcceptsZeroBytes() {
        assertFalse(SignalStoreLoadPolicy.isPersistable("   ", byteArrayOf(1)))
        assertTrue(SignalStoreLoadPolicy.isPersistable("prekey:1", byteArrayOf(0, 0)))
    }

    @Test
    fun logicalKeyRejectsBlankPrefixAndBlankRemainder() {
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("session:u1:1", " "))
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("user:alice:   ", "user:alice:"))
    }

    @Test
    fun logicalKeyPrefixMatchIsCaseSensitive() {
        assertEquals(
            "User:alice:session:u1:1",
            SignalStoreLoadPolicy.logicalKeyOrNull("User:alice:session:u1:1", "user:alice:"),
        )
    }


    @Test
    fun logicalKeyStripsOnlyFirstPrefixOccurrence() {
        assertEquals(
            "user:alice:x",
            SignalStoreLoadPolicy.logicalKeyOrNull("user:alice:user:alice:x", "user:alice:"),
        )
    }

    @Test
    fun logicalKeyRejectsBlankPrefix() {
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("session:u1:1", ""))
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("session:u1:1", "   "))
    }

    @Test
    fun logicalKeyKeepsKeyWhenPrefixIsNotAHeadMatch() {
        assertEquals(
            "my-session:u1",
            SignalStoreLoadPolicy.logicalKeyOrNull("my-session:u1", "session:"),
        )
    }

    @Test
    fun shouldLoadRowAcceptsNewlinePaddedKeyData() {
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "\nYWJj\n"))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "\n  \n"))
    }

    @Test
    fun isPersistableRejectsBlankKeyTypeVariants() {
        assertFalse(SignalStoreLoadPolicy.isPersistable("\t", byteArrayOf(1)))
        assertFalse(SignalStoreLoadPolicy.isPersistable("\n", byteArrayOf(1)))
    }
}
