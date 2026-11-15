package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PublishPostValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishPostValidationPolicyTest {

    @Test
    fun `empty content with no images is invalid`() {
        val result = PublishPostValidationPolicy.validate("   ", emptyList())
        assertTrue(result is PublishPostValidation.Invalid)
        assertEquals(
            "Post content or image cannot be empty",
            (result as PublishPostValidation.Invalid).reason
        )
    }

    @Test
    fun `empty content with images is valid`() {
        val result = PublishPostValidationPolicy.validate(
            "   ",
            listOf("https://example.com/img.jpg")
        )
        assertEquals(PublishPostValidation.Valid, result)
    }

    @Test
    fun `content over 5000 chars is invalid`() {
        val result = PublishPostValidationPolicy.validate("x".repeat(5001), emptyList())
        assertTrue(result is PublishPostValidation.Invalid)
        assertEquals(
            "Post content exceeds 5000 characters",
            (result as PublishPostValidation.Invalid).reason
        )
    }

    @Test
    fun `content of exactly 5000 chars is valid`() {
        val result = PublishPostValidationPolicy.validate("x".repeat(5000), emptyList())
        assertEquals(PublishPostValidation.Valid, result)
    }

    @Test
    fun `normal content is valid`() {
        val result = PublishPostValidationPolicy.validate("Hello World!", emptyList())
        assertEquals(PublishPostValidation.Valid, result)
    }
}
