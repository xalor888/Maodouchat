package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishPostUseCaseModelsTest {

    @Test
    fun `valid is a singleton object`() {
        assertTrue(PublishPostValidation.Valid is PublishPostValidation)
    }

    @Test
    fun `invalid keeps its reason`() {
        val result = PublishPostValidation.Invalid("empty")
        assertEquals("empty", result.reason)
        assertTrue(result is PublishPostValidation)
    }
}
