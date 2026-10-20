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

    @Test
    fun logicalKeyReturnsNullWhenPrefixConsumesWholeKey() {
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("user:alice:", "user:alice:"))
        assertNull(SignalStoreLoadPolicy.logicalKeyOrNull("prekey:1", "prekey:1"))
    }

    @Test
    fun logicalKeyKeepsKeyWhenPrefixIsLonger() {
        assertEquals("ab", SignalStoreLoadPolicy.logicalKeyOrNull("ab", "abcdef"))
    }

    @Test
    fun logicalKeyDoesNotTrimRemainder() {
        // 剥前缀后只判非空，剩余部分原样保留不做 trim
        assertEquals("  x  ", SignalStoreLoadPolicy.logicalKeyOrNull("user:alice:  x  ", "user:alice:"))
    }

    @Test
    fun persistableAcceptsKeyTypeWithInternalSpaces() {
        assertTrue(SignalStoreLoadPolicy.isPersistable("pre key:1", byteArrayOf(1)))
    }

    @Test
    fun loadRowAcceptsNewlinePaddedKeyType() {
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("\nsession:u1:1\n", "YWJj"))
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("\n  \n", "YWJj"))
    }

    @Test
    fun loadRowDropsTabPaddedBlankKeyData() {
        assertFalse(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "\t \t"))
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "\tYWJj\t"))
    }

    @Test
    fun loadRowZeroWidthSpaceIsNotBlank() {
        // U+200B 不是 Unicode 空白，isBlank 判它非空
        assertTrue(SignalStoreLoadPolicy.shouldLoadRow("session:u1:1", "\u200B"))
    }

    @Test
    fun isPersistableAcceptsPaddedKeyTypeButRejectsEmpty() {
        assertTrue(SignalStoreLoadPolicy.isPersistable("  prekey:1  ", byteArrayOf(0)))
        assertFalse(SignalStoreLoadPolicy.isPersistable("", byteArrayOf(1)))
    }

    @Test
    fun logicalKeyPrefixMatchIsLiteral() {
        // startsWith 是字面匹配，* 不当通配符
        assertEquals("x", SignalStoreLoadPolicy.logicalKeyOrNull("user*alice:x", "user*alice:"))
        assertEquals("x", SignalStoreLoadPolicy.logicalKeyOrNull("prekey:1x", "prekey:1"))
    }

    @Test
    fun logicalKeyReturnsKeyVerbatimWhenPrefixNotAtHead() {
        assertEquals("  session:u1  ", SignalStoreLoadPolicy.logicalKeyOrNull("  session:u1  ", "session:"))
    }
}
