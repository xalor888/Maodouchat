package com.maodouchat.domain.groups

// 群内昵称输入上限：与服务端 group_nickname varchar(100) 一致。
object GroupNicknamePolicy {

    const val MAX_LENGTH: Int = 100

    // 输入过滤：截 100 字符，不做 trim（与原输入框行为一致）。
    fun filterInput(raw: String): String = raw.take(MAX_LENGTH)
}
