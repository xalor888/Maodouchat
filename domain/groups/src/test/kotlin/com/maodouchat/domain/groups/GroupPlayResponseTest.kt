package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupPlayResponseTest {

    @Test
    fun `ok response has no error text`() {
        assertNull(GroupPlayResponse(ok = true, body = "{\"error\":\"x\"}", code = 200).errorText)
    }

    @Test
    fun `failed response surfaces server error`() {
        val resp = GroupPlayResponse(ok = false, body = "{\"error\":\"not found\"}", code = 404)
        assertEquals("not found", resp.errorText)
    }

    @Test
    fun `failed response without error body has no error text`() {
        assertNull(GroupPlayResponse(ok = false, body = "", code = -1).errorText)
    }
}
