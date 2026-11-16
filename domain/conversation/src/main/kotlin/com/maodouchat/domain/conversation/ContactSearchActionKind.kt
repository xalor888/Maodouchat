package com.maodouchat.domain.conversation

// 联系人搜索行操作种类；按钮/文案映射仍在 app 侧。
enum class ContactSearchActionKind {
    START_CHAT,
    REQUEST_PENDING,
    ADD_FRIEND,
}

fun contactSearchActionKind(isFriend: Boolean, requestPending: Boolean): ContactSearchActionKind =
    when {
        isFriend -> ContactSearchActionKind.START_CHAT
        requestPending -> ContactSearchActionKind.REQUEST_PENDING
        else -> ContactSearchActionKind.ADD_FRIEND
    }
