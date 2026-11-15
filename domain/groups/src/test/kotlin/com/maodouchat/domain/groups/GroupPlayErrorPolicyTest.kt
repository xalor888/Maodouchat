package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupPlayErrorPolicyTest {

    @Test
    fun `error key after colon prefix is extracted`() {
        assertEquals("服务端忙", GroupPlayErrorPolicy.serverErrorText("HTTP 500: {\"error\":\"服务端忙\"}"))
    }

    @Test
    fun `message key after colon prefix is extracted`() {
        assertEquals("oops", GroupPlayErrorPolicy.serverErrorText("Error: {\"message\":\"oops\"}"))
    }

    @Test
    fun `plain text without colon yields null`() {
        assertNull(GroupPlayErrorPolicy.serverErrorText("boom"))
        assertNull(GroupPlayErrorPolicy.serverErrorText(""))
    }

    @Test
    fun `non json after colon yields null`() {
        assertNull(GroupPlayErrorPolicy.serverErrorText("500: not json"))
    }

    @Test
    fun `json without error or message yields null`() {
        assertNull(GroupPlayErrorPolicy.serverErrorText("x: {}"))
    }

    @Test
    fun `only first colon is stripped`() {
        assertNull(GroupPlayErrorPolicy.serverErrorText("a: b: {\"error\":\"e\"}"))
    }
}
