package com.maodouchat.ui.screen.chatdetail

data class ActiveQuery(
    /** `@` 在全文中的起始下标 */
    val atIndex: Int,
    /** `@` 之后、光标之前的过滤串（不含 @） */
    val filter: String,
)

data class Candidate(
    val userId: String,
    val displayName: String,
    val isEveryone: Boolean = false,
)

data class InsertResult(
    val text: String,
    /** 建议光标位置（插入段末尾） */
    val cursor: Int,
)
