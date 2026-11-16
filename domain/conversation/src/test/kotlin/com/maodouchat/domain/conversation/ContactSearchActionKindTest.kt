package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class ContactSearchActionKindTest {

    @Test
    fun `friend starts chat`() {
        assertEquals(ContactSearchActionKind.START_CHAT, contactSearchActionKind(true, false))
    }

    @Test
    fun `pending request shows pending`() {
        assertEquals(ContactSearchActionKind.REQUEST_PENDING, contactSearchActionKind(false, true))
    }

    @Test
    fun `stranger can be added`() {
        assertEquals(ContactSearchActionKind.ADD_FRIEND, contactSearchActionKind(false, false))
    }

    @Test
    fun `friend wins over pending`() {
        assertEquals(ContactSearchActionKind.START_CHAT, contactSearchActionKind(true, true))
    }
}
