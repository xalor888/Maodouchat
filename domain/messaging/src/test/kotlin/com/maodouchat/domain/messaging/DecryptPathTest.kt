package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class DecryptPathTest {

    @Test
    fun `sender key ciphertext takes the group path`() {
        assertEquals(DecryptPath.GROUP, decideDecryptPath("SENDER_KEY"))
    }

    @Test
    fun `anything else takes the device path`() {
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("TEXT"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SERVICE_PLAINTEXT"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath(""))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("sender_key"))
    }
}
