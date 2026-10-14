package com.maodouchat.explore.usecase

sealed class PublishPostValidation {
    object Valid : PublishPostValidation()
    data class Invalid(val reason: String) : PublishPostValidation()
}
