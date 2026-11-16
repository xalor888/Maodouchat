package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountDeviceFingerprintPolicyTest {

    @Test
    fun `identity key head and tail`() {
        assertEquals("abcdef12", AccountDeviceFingerprintPolicy.identityKeyHead("abcdef123456"))
        assertEquals("123456", AccountDeviceFingerprintPolicy.identityKeyTail("abcdef123456"))
        assertEquals("abc", AccountDeviceFingerprintPolicy.identityKeyHead("abc"))
        assertEquals("abc", AccountDeviceFingerprintPolicy.identityKeyTail("abc"))
    }

    @Test
    fun `fingerprint strips spaces and uppercases`() {
        assertEquals("ABCDEF12", AccountDeviceFingerprintPolicy.shortFingerprint("ab cd ef12 34"))
        assertEquals("ABCD", AccountDeviceFingerprintPolicy.shortFingerprint("abcd"))
    }

    @Test
    fun `null fingerprint stays null`() {
        assertNull(AccountDeviceFingerprintPolicy.shortFingerprint(null))
    }
}
