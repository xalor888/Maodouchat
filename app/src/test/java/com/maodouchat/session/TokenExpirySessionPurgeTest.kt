package com.maodouchat.session

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U02 延伸：`TokenExpirySessionPurge.shouldHandle` 的世代/归属判定测试（JVM）。
 *
 * 这三条判据是 401/踢线清理的安全网：登出或换号后送达的旧事件必须被丢弃，
 * 否则会把**新账号**的本地会话当成旧账号的清掉（或反之，残留旧会话）。
 * `CurrentSession.override` 是它 KDoc 里明确留给 JVM 单测的缝（生产不得设置）。
 */
class TokenExpirySessionPurgeTest {

    private val currentGeneration = MaodouchatApp.currentSessionGeneration()

    @After
    fun tearDown() {
        CurrentSession.override = null
    }

    private fun event(owner: String, generation: Long) =
        ApiService.TokenExpiredEvent(ownerUserId = owner, sessionGeneration = generation)

    @Test
    fun matchingOwnerAndGenerationIsHandled() {
        CurrentSession.override = { CurrentSession.Snapshot("tok", "u-1") }
        assertTrue(TokenExpirySessionPurge.shouldHandle(event("u-1", currentGeneration)))
    }

    @Test
    fun mismatchedGenerationIsNotHandled() {
        CurrentSession.override = { CurrentSession.Snapshot("tok", "u-1") }
        assertFalse(TokenExpirySessionPurge.shouldHandle(event("u-1", currentGeneration - 1L)))
    }

    @Test
    fun otherOwnerIsNotHandled() {
        CurrentSession.override = { CurrentSession.Snapshot("tok", "u-1") }
        assertFalse(TokenExpirySessionPurge.shouldHandle(event("u-2", currentGeneration)))
    }

    @Test
    fun blankOwnerIsNotHandled() {
        CurrentSession.override = { CurrentSession.Snapshot("tok", "u-1") }
        assertFalse(TokenExpirySessionPurge.shouldHandle(event("", currentGeneration)))
    }

    @Test
    fun noLocalSessionIsNotHandled() {
        CurrentSession.override = { CurrentSession.Snapshot(null, null) }
        assertFalse(TokenExpirySessionPurge.shouldHandle(event("u-1", currentGeneration)))
    }
}
