package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiHttpErrorsTest {

    @Test
    fun `http error keeps status code and server code`() {
        val error = ApiHttpErrors.apiExceptionFromHttp(
            404, "{\"error\":\"not found\",\"code\":\"NOT_FOUND\"}"
        )
        assertEquals(ApiFailureKind.HTTP, error.kind)
        assertEquals(404, error.statusCode)
        assertEquals("not found", error.serverMessage)
        assertEquals("NOT_FOUND", error.serverCode)
        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun `http error reads retry after header`() {
        val error = ApiHttpErrors.apiExceptionFromHttp(
            500, "{\"error\":\"busy\"}",
            retryAfterHeader = "30", url = "https://example.invalid/x?token=secret"
        )
        assertEquals(500, error.statusCode)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `session changed result is 409 with session changed body`() {
        val result = ApiHttpErrors.sessionChangedResult()
        assertEquals(409, result.code)
        assertEquals(false, result.isSuccessful)
        assertEquals("{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}", result.body)
    }

    @Test
    fun `session changed exception is a 409 http error`() {
        val error = ApiHttpErrors.sessionChangedException()
        assertEquals(ApiFailureKind.HTTP, error.kind)
        assertEquals(409, error.statusCode)
        assertEquals("SESSION_CHANGED", error.serverCode)
    }
}
