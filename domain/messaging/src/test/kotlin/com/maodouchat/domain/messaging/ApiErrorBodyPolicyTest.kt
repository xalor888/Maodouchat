package com.maodouchat.domain.messaging

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiErrorBodyPolicyTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    @Test
    fun errorResponseOptionalFieldsDefaultNull() {
        val decoded = json.decodeFromString<ErrorResponse>("""{"error":"boom"}""")
        assertEquals("boom", decoded.error)
        assertNull(decoded.code)
        assertNull(decoded.retryAfterSeconds)
    }

    @Test
    fun errorResponseWithCodeAndRetryAfterDecodes() {
        val decoded = json.decodeFromString<ErrorResponse>(
            """{"error":"slow","code":"RATE_LIMITED","retryAfterSeconds":30}"""
        )
        assertEquals("slow", decoded.error)
        assertEquals("RATE_LIMITED", decoded.code)
        assertEquals(30L, decoded.retryAfterSeconds)
    }
}
