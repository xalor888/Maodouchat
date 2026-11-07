package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushVerifyKeyPayloadTest {

    @Test
    fun `blank raw is ignored`() {
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload(""))
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("   "))
    }

    @Test
    fun `invalid json is ignored`() {
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("not json"))
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("{"))
    }

    @Test
    fun `non object top level is ignored`() {
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("\"abc\""))
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("123"))
    }

    @Test
    fun `explicit null key clears`() {
        assertEquals(PushVerifyKeyAction.Clear, parsePushVerifyKeyPayload("""{"key":null}"""))
    }

    @Test
    fun `missing key clears like org json isNull`() {
        assertEquals(PushVerifyKeyAction.Clear, parsePushVerifyKeyPayload("{}"))
        assertEquals(PushVerifyKeyAction.Clear, parsePushVerifyKeyPayload("""{"other":"x"}"""))
    }

    @Test
    fun `present key sets`() {
        val action = parsePushVerifyKeyPayload("""{"key":"abc"}""")
        assertTrue(action is PushVerifyKeyAction.Set)
        assertEquals("abc", (action as PushVerifyKeyAction.Set).key)
    }

    @Test
    fun `blank key is ignored`() {
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("""{"key":""}"""))
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("""{"key":"  "}"""))
    }

    @Test
    fun `literal null string is ignored`() {
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("""{"key":"null"}"""))
    }

    @Test
    fun `number and boolean keys take literal text like optString`() {
        val number = parsePushVerifyKeyPayload("""{"key":123}""")
        assertTrue(number is PushVerifyKeyAction.Set)
        assertEquals("123", (number as PushVerifyKeyAction.Set).key)
        val bool = parsePushVerifyKeyPayload("""{"key":true}""")
        assertTrue(bool is PushVerifyKeyAction.Set)
        assertEquals("true", (bool as PushVerifyKeyAction.Set).key)
    }

    @Test
    fun `object and array keys are ignored`() {
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("""{"key":{"a":1}}"""))
        assertEquals(PushVerifyKeyAction.Ignore, parsePushVerifyKeyPayload("""{"key":["x"]}"""))
    }
}
