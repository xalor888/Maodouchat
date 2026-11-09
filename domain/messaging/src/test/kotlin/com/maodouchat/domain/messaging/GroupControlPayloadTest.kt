package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupControlPayloadTest {

    @Test
    fun `sender key distribution encrypts the decoded body as sk dist`() {
        assertEquals(
            GroupControlPayload(useDecodedBody = true, payloadType = "SK_DIST"),
            decideGroupControlPayload("SENDER_KEY"),
        )
    }

    @Test
    fun `key requests encrypt the raw payload as key request`() {
        assertEquals(
            GroupControlPayload(useDecodedBody = false, payloadType = "KEY_REQUEST"),
            decideGroupControlPayload("KEY_REQUEST"),
        )
    }

    @Test
    fun `other group control kinds fall back to the raw payload`() {
        assertEquals(
            GroupControlPayload(useDecodedBody = false, payloadType = "KEY_REQUEST"),
            decideGroupControlPayload("DATA"),
        )
    }
}
