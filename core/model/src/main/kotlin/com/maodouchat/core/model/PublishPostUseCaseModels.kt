package com.maodouchat.core.model

sealed class PublishPostValidation {
    object Valid : PublishPostValidation()
    data class Invalid(val reason: String) : PublishPostValidation()
}
