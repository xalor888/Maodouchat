package com.maodouchat.ui.screen.explore

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorePublishErrorPolicyTest {

    @Test
    fun classifiesPostRestrictionFromServerCopy() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "你已被限制发布动态，解除时间 2026-08-01"
        )
        assertEquals(Kind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(err))
        val shown = ExplorePublishErrorPolicy.displayMessage(err, "发布失败")
        assertTrue(shown.contains("限制发布动态"))
    }

    @Test
    fun classifiesMessageRestriction() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "你已被限制发消息"
        )
        assertEquals(Kind.MESSAGE_RESTRICTED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun classifiesSuspended() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "账号已被临时封禁"
        )
        assertEquals(Kind.SUSPENDED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun authOn401() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 401,
            serverMessage = "Unauthorized"
        )
        assertEquals(Kind.AUTH, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun fallbackKeepsGenericMessage() {
        val err = ApiException(
            kind = ApiFailureKind.NETWORK,
            statusCode = 500,
            serverMessage = null
        )
        assertEquals(Kind.FALLBACK, ExplorePublishErrorPolicy.classify(err))
        assertEquals("发布失败", ExplorePublishErrorPolicy.displayMessage(err, "发布失败"))
    }

    @Test
    fun classifiesPostRestrictionByStatusAndDynamic() {
        // 文案里没有限制关键词，但 403 + "动态" 就够了
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "动态发布暂时不可用"
        )
        assertEquals(Kind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun classifiesSuspendedByStatusAndBanCopy() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "账号封禁中"
        )
        assertEquals(Kind.SUSPENDED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun postRestrictionWinsOverSuspendedCopy() {
        // 文案同时命中两处关键词：动态限制的分支先检查
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "限制发布动态，账号存在封禁记录"
        )
        assertEquals(Kind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun displayMessagePrefersServerCopyForSuspended() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "账号已被临时封禁，解封时间 2026-09-01"
        )
        assertEquals(
            "账号已被临时封禁，解封时间 2026-09-01",
            ExplorePublishErrorPolicy.displayMessage(err, "发布失败")
        )
    }

    @Test
    fun authKeywordWithout401FallsBack() {
        // 文案像登录失效但状态码不是 401：不算 AUTH
        val err = ApiException(
            kind = ApiFailureKind.NETWORK,
            statusCode = 500,
            serverMessage = "token 已过期"
        )
        assertEquals(Kind.FALLBACK, ExplorePublishErrorPolicy.classify(err))
    }
}
