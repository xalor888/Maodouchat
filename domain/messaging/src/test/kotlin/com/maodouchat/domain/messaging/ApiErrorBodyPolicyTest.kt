package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiErrorBodyPolicyTest {

    @Test
    fun `valid error body decodes`() {
        val out = ApiErrorBodyPolicy.parseErrorResponse(
            """{"error":"oops","code":"X","retryAfterSeconds":30}"""
        )
        assertEquals("oops", out?.error)
        assertEquals("X", out?.code)
        assertEquals(30L, out?.retryAfterSeconds)
    }

    @Test
    fun `non json body gives null`() {
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("not-json"))
    }

    @Test
    fun `blank error gives null`() {
        assertNull(ApiErrorBodyPolicy.parseError("""{"error":"   "}"""))
    }

    @Test
    fun `retry after from body caps at one day`() {
        assertEquals(
            86_400L,
            ApiErrorBodyPolicy.parseRetryAfterSeconds("""{"error":"x","retryAfterSeconds":90000}""")
        )
    }

    @Test
    fun `retry after falls back to header`() {
        assertEquals(120L, ApiErrorBodyPolicy.parseRetryAfterSeconds("{}", "120"))
    }

    @Test
    fun `invalid retry after gives null`() {
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{}", "abc"))
    }
}
