package com.maodouchat.domain.groups

data class GroupPlayResponse(
    val ok: Boolean,
    val body: String,
    val code: Int
) {
    val errorText: String?
        get() = if (ok) null else GroupPlayJson.errorMessage(body)
}
