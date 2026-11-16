package com.maodouchat.domain.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFolderStripPolicyTest {

    @Test
    fun `secret chip shows with count or when its folder is selected`() {
        assertTrue(ChatFolderStripPolicy.showSecretChip(1, null))
        assertTrue(ChatFolderStripPolicy.showSecretChip(0, "system:secret"))
        assertFalse(ChatFolderStripPolicy.showSecretChip(0, null))
        assertFalse(ChatFolderStripPolicy.showSecretChip(0, "system:locked"))
    }

    @Test
    fun `locked chip shows with count or when its folder is selected`() {
        assertTrue(ChatFolderStripPolicy.showLockedChip(2, null))
        assertTrue(ChatFolderStripPolicy.showLockedChip(0, "system:locked"))
        assertFalse(ChatFolderStripPolicy.showLockedChip(0, null))
        assertFalse(ChatFolderStripPolicy.showLockedChip(0, "system:secret"))
    }
}
