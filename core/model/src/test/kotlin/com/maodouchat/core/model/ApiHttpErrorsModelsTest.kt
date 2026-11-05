package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHttpErrorsModelsTest {

    @Test
    fun `http result keeps code status and body`() {
        val result = HttpResult(200, true, "ok")
        assertEquals(200, result.code)
        assertTrue(result.isSuccessful)
        assertEquals("ok", result.body)
        assertEquals(HttpResult(404, false, ""), result.copy(code = 404, isSuccessful = false, body = ""))
    }
}
