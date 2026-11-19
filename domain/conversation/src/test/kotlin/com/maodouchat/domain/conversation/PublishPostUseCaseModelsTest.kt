package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

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
