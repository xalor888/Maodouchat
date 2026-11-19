package com.maodouchat.domain.conversation

sealed class PublishPostValidation {
    object Valid : PublishPostValidation()
    data class Invalid(val reason: String) : PublishPostValidation()
}
