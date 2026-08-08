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
        assertEquals(ExplorePublishErrorPolicy.Kind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(err))
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
        assertEquals(ExplorePublishErrorPolicy.Kind.MESSAGE_RESTRICTED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun classifiesSuspended() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 403,
            serverMessage = "账号已被临时封禁"
        )
        assertEquals(ExplorePublishErrorPolicy.Kind.SUSPENDED, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun authOn401() {
        val err = ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = 401,
            serverMessage = "Unauthorized"
        )
        assertEquals(ExplorePublishErrorPolicy.Kind.AUTH, ExplorePublishErrorPolicy.classify(err))
    }

    @Test
    fun fallbackKeepsGenericMessage() {
        val err = ApiException(
            kind = ApiFailureKind.NETWORK,
            statusCode = 500,
            serverMessage = null
        )
        assertEquals(ExplorePublishErrorPolicy.Kind.FALLBACK, ExplorePublishErrorPolicy.classify(err))
        assertEquals("发布失败", ExplorePublishErrorPolicy.displayMessage(err, "发布失败"))
    }
}
