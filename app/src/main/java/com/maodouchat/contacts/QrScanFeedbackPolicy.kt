package com.maodouchat.contacts

/**
 * Maps QR parse / join-invite failures to stable, UI-localizable kinds (W3-04).
 * Does not hold string resources — callers map [Kind] → R.string.
 */
object QrScanFeedbackPolicy {
    enum class Kind {
        INVALID_PAYLOAD,
        SESSION_EXPIRED,
        USER_NOT_FOUND,
        INVITE_INVALID_OR_EXPIRED,
        INVITE_BLOCKED,
        GROUP_FULL,
        NETWORK,
        UNKNOWN
    }

    data class Feedback(
        val kind: Kind,
        /** Prefer serverCode when present for support/debug; UI may ignore. */
        val serverCode: String? = null,
        val retryable: Boolean = false
    )

    fun forUnparseablePayload(): Feedback = Feedback(Kind.INVALID_PAYLOAD)

    fun forUserLookupMiss(): Feedback = Feedback(Kind.USER_NOT_FOUND)

    fun forSessionExpired(): Feedback = Feedback(Kind.SESSION_EXPIRED)

    /** User QR lookup via GET /users/{id} — keep miss vs session vs network distinct. */
    fun forUserLookup(
        httpStatus: Int?,
        isNetwork: Boolean = false,
        isTimeout: Boolean = false
    ): Feedback {
        if (isNetwork || isTimeout) {
            return Feedback(Kind.NETWORK, retryable = true)
        }
        return when (httpStatus) {
            401 -> forSessionExpired()
            404 -> forUserLookupMiss()
            else -> Feedback(Kind.UNKNOWN, retryable = true)
        }
    }

    fun forJoinInvite(
        httpStatus: Int?,
        serverCode: String?,
        serverMessage: String?,
        isNetwork: Boolean = false,
        isTimeout: Boolean = false
    ): Feedback {
        if (isNetwork || isTimeout) {
            return Feedback(Kind.NETWORK, serverCode = serverCode, retryable = true)
        }
        val code = serverCode?.trim()?.uppercase().orEmpty()
        when (code) {
            "GROUP_INVITE_BLOCKED" -> return Feedback(Kind.INVITE_BLOCKED, serverCode = code)
            "GROUP_MEMBER_LIMIT_EXCEEDED" -> return Feedback(Kind.GROUP_FULL, serverCode = code)
        }
        return when (httpStatus) {
            401 -> Feedback(Kind.SESSION_EXPIRED, serverCode = code.ifBlank { null })
            400, 404 -> Feedback(Kind.INVITE_INVALID_OR_EXPIRED, serverCode = code.ifBlank { null })
            403 -> Feedback(Kind.INVITE_BLOCKED, serverCode = code.ifBlank { null })
            409 -> Feedback(Kind.GROUP_FULL, serverCode = code.ifBlank { null })
            else -> {
                val msg = serverMessage.orEmpty()
                when {
                    msg.contains("屏蔽") || msg.contains("block", ignoreCase = true) ->
                        Feedback(Kind.INVITE_BLOCKED, serverCode = code.ifBlank { null })
                    msg.contains("上限") || msg.contains("limit", ignoreCase = true) ->
                        Feedback(Kind.GROUP_FULL, serverCode = code.ifBlank { null })
                    msg.contains("失效") || msg.contains("无效") || msg.contains("expired", ignoreCase = true) ->
                        Feedback(Kind.INVITE_INVALID_OR_EXPIRED, serverCode = code.ifBlank { null })
                    else -> Feedback(Kind.UNKNOWN, serverCode = code.ifBlank { null }, retryable = true)
                }
            }
        }
    }
}
