package com.maodouchat.core.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptPlaceholderPolicyTest {

    @Test
    fun `legacy placeholders are recognized`() {
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("无法解密"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("[unable to decrypt message]"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("  [Encrypted message]  "))
    }

    @Test
    fun `ordinary sentences are not placeholders`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("这个消息该怎么解密？"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("decrypt this message"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder(""))
    }

    @Test
    fun `localized placeholders match after normalization`() {
        assertTrue(
            DecryptPlaceholderPolicy.isPlaceholder(
                "Nachricht konnte nicht entschlüsselt werden",
                "Nachricht konnte nicht entschlüsselt werden"
            )
        )
    }
}
