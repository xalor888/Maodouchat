package com.maodouchat.domain.messaging

/**
 * 失败落库用的错误码：异常消息截断到 120 字，空白或缺失时回落到异常类名。
 * 收件箱与发件箱两处原先各写了一份，逐字合一。
 */
fun messagingErrorCode(error: Throwable): String =
    error.message?.take(120)?.ifBlank { null } ?: error::class.java.simpleName
