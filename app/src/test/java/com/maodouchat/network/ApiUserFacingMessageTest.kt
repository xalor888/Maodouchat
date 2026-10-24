package com.maodouchat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    fun httpKindWithoutServerMessageUsesFallback() {
        val error = ApiException(kind = ApiFailureKind.HTTP, statusCode = 500)
        assertEquals(
            "操作失败",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun unexpectedKindWithoutServerMessageUsesFallback() {
        val error = ApiException(kind = ApiFailureKind.UNEXPECTED)
        assertEquals(
            "操作失败",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun nonHttpKindPrefersNonBlankServerMessage() {
        val error = ApiException(kind = ApiFailureKind.NETWORK, serverMessage = "DNS 解析失败")
        assertEquals(
            "DNS 解析失败",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun serverMessageWithSurroundingWhitespaceIsTrimmed() {
        val error = ApiException(kind = ApiFailureKind.HTTP, serverMessage = "  余额不足  ")
        assertEquals(
            "余额不足",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun nullMessageThrowableUsesFallback() {
        assertEquals(
            "操作失败",
            RuntimeException().toUserFacingMessage("网络", "超时", "无效", "操作失败"),
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

    @Test
    fun timeoutKindPrefersNonBlankServerMessage() {
        val error = ApiException(kind = ApiFailureKind.TIMEOUT, serverMessage = "网关超时")
        assertEquals(
            "网关超时",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun connectExceptionMarksConnectionNeverEstablished() {
        val error = apiExceptionForIOException(IOException(ConnectException("refused")))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun nestedUnknownHostCauseMarksConnectionNeverEstablished() {
        val error = apiExceptionForIOException(IOException("wrap", UnknownHostException()))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun socketTimeoutMayHaveReachedServer() {
        val error = apiExceptionForIOException(SocketTimeoutException())
        assertEquals(ApiFailureKind.TIMEOUT, error.kind)
        assertTrue(error.requestMayHaveReachedServer)
    }

    @Test
    fun httpKindBlankServerMessageUsesFallback() {
        val error = ApiException(kind = ApiFailureKind.HTTP, statusCode = 403, serverMessage = "   ")
        assertEquals(
            "操作失败",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun networkKindBlankServerMessageUsesNetworkCopy() {
        val error = ApiException(kind = ApiFailureKind.NETWORK, serverMessage = "   ")
        assertEquals(
            "网络",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun timeoutKindBlankServerMessageUsesTimeoutCopy() {
        val error = ApiException(kind = ApiFailureKind.TIMEOUT, serverMessage = "  ")
        assertEquals(
            "超时",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun unexpectedKindWithServerMessagePrefersServerMessage() {
        val error = ApiException(kind = ApiFailureKind.UNEXPECTED, serverMessage = "未知错误")
        assertEquals(
            "未知错误",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun noRouteToHostMarksConnectionNeverEstablished() {
        val error = apiExceptionForIOException(IOException(NoRouteToHostException("no route")))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun nestedSocketTimeoutIsNetworkButMayHaveReachedServer() {
        val error = apiExceptionForIOException(IOException("wrap", SocketTimeoutException()))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertTrue(error.requestMayHaveReachedServer)
    }

    @Test
    fun plainIOExceptionIsNetworkAndMayHaveReachedServer() {
        val error = apiExceptionForIOException(IOException("reset by peer"))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertTrue(error.requestMayHaveReachedServer)
    }

    @Test
    fun directUnknownHostMarksConnectionNeverEstablished() {
        val error = apiExceptionForIOException(UnknownHostException())
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun deeplyNestedConnectExceptionMarksConnectionNeverEstablished() {
        val error = apiExceptionForIOException(IOException("a", IOException("b", ConnectException("refused"))))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun invalidResponseKindPrefersNonBlankServerMessage() {
        val error = ApiException(kind = ApiFailureKind.INVALID_RESPONSE, serverMessage = "响应格式异常")
        assertEquals(
            "响应格式异常",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun serverMessageWithTabsAndNewlinesIsTrimmed() {
        val error = ApiException(kind = ApiFailureKind.HTTP, serverMessage = "\n\t余额不足\n")
        assertEquals(
            "余额不足",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun serverMessageInternalWhitespaceIsPreserved() {
        val error = ApiException(kind = ApiFailureKind.HTTP, serverMessage = " 余额 不足 ")
        assertEquals(
            "余额 不足",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun serverMessageWithCarriageReturnsIsTrimmed() {
        val error = ApiException(kind = ApiFailureKind.HTTP, serverMessage = "\r余额不足\r")
        assertEquals(
            "余额不足",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }

    @Test
    fun topLevelTimeoutWithUnknownHostCauseIsTimeoutButNeverEstablished() {
        val error = apiExceptionForIOException(
            SocketTimeoutException().apply { initCause(UnknownHostException()) }
        )
        assertEquals(ApiFailureKind.TIMEOUT, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun directNoRouteToHostIsNetworkAndNeverEstablished() {
        val error = apiExceptionForIOException(NoRouteToHostException("no route"))
        assertEquals(ApiFailureKind.NETWORK, error.kind)
        assertFalse(error.requestMayHaveReachedServer)
    }

    @Test
    fun httpSuccessStatusCodeStillPrefersServerMessage() {
        val error = ApiException(kind = ApiFailureKind.HTTP, statusCode = 200, serverMessage = "ok")
        assertEquals(
            "ok",
            error.toUserFacingMessage("网络", "超时", "无效", "操作失败"),
        )
    }
}
