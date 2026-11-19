package com.maodouchat.domain.groups

import com.maodouchat.core.model.Kind

data class Feedback(
    val kind: Kind,
    /** Prefer serverCode when present for support/debug; UI may ignore. */
    val serverCode: String? = null,
    val retryable: Boolean = false
)
