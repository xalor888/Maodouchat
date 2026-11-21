package com.maodouchat.core.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketCloseReasonPolicyTest {
    @Test
    fun authDeathCoversSessionInvalidated() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("会话已失效，请重新登录"))
    }

    @Test
    fun authDeathCoversLoggedOut() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("账号已退出"))
    }

    @Test
    fun authDeathCoversPasswordChanged() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("密码已修改"))
    }

    @Test
    fun authDeathCoversAccountDeleted() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("账号已注销"))
    }

    @Test
    fun authDeathCoversBanned() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("账号封禁"))
    }

    @Test
    fun authDeathCoversRemoved() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("设备已被移除"))
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("设备被移除"))
    }

    @Test
    fun authDeathCoversEnglishKeywordsCaseInsensitive() {
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("UNAUTHENTICATED"))
        assertTrue(WebSocketCloseReasonPolicy.isAuthDeathReason("Token Revoked"))
    }

    @Test
    fun authDeathRejectsNullBlankAndUnrelated() {
        assertFalse(WebSocketCloseReasonPolicy.isAuthDeathReason(null))
        assertFalse(WebSocketCloseReasonPolicy.isAuthDeathReason(""))
        assertFalse(WebSocketCloseReasonPolicy.isAuthDeathReason("连接数超限"))
        assertFalse(WebSocketCloseReasonPolicy.isAuthDeathReason("network error"))
    }

    @Test
    fun expiryIsRecoverableNotAuthDeath() {
        assertTrue(WebSocketCloseReasonPolicy.isRecoverableExpiryReason("会话已过期"))
        assertFalse(WebSocketCloseReasonPolicy.isAuthDeathReason("会话已过期"))
        assertTrue(WebSocketCloseReasonPolicy.isRecoverableExpiryReason("access token expired"))
        assertFalse(WebSocketCloseReasonPolicy.isAuthDeathReason("access token expired"))
    }

    @Test
    fun recoverableExpiryCoversTokenWord() {
        assertTrue(WebSocketCloseReasonPolicy.isRecoverableExpiryReason("token invalid"))
        assertTrue(WebSocketCloseReasonPolicy.isRecoverableExpiryReason("TOKEN EXPIRES SOON"))
    }

    @Test
    fun recoverableExpiryRejectsNullBlankAndUnrelated() {
        assertFalse(WebSocketCloseReasonPolicy.isRecoverableExpiryReason(null))
        assertFalse(WebSocketCloseReasonPolicy.isRecoverableExpiryReason(""))
        assertFalse(WebSocketCloseReasonPolicy.isRecoverableExpiryReason("会话已失效"))
        assertFalse(WebSocketCloseReasonPolicy.isRecoverableExpiryReason("连接数超限"))
    }
}
