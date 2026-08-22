package com.maodouchat.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ApiUserFacingMessageTest {
    @Test
    fun httpPrefersServerMessage() {
        val error = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 400,
            serverMessage = "邮箱或密码错误",
        )
        assertEquals(
            "邮箱或密码错误",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun networkKindWithoutServerMessageUsesNetworkCopy() {
        val error = apiExceptionForIOException(IOException("failed to connect"))
        assertEquals(
            "网络不可用",
            error.toUserFacingMessage("网络不可用", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun timeoutKindUsesTimeoutCopy() {
        val error = apiExceptionForIOException(SocketTimeoutException())
        assertEquals(
            "请求超时",
            error.toUserFacingMessage("网络不可用", "请求超时", "无效", "操作失败"),
        )
    }

    @Test
    fun blankServerMessageFallsBackByKind() {
        val error = ApiException(
            kind = ApiFailureKind.INVALID_RESPONSE,
            serverMessage = "  ",
        )
        assertEquals(
            "无效",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun genericThrowableUsesMessageThenFallback() {
        assertEquals(
            "boom",
            IllegalStateException("boom").toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
        assertEquals(
            "操作失败",
            RuntimeException("  ").toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }
}
