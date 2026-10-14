package com.maodouchat.util

enum class LocationFailure {
    PERMISSION_REQUIRED,
    SERVICES_DISABLED,
    UNAVAILABLE
}

class LocationException(
    val failure: LocationFailure,
    cause: Throwable? = null
) : Exception(cause)
