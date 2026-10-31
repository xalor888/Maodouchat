package com.maodouchat.chatdetail

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.group.GroupMutationAction
import com.maodouchat.group.GroupMutationFeedbackKind
import com.maodouchat.group.GroupMutationFeedbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GroupMutationFeedbackPolicyTest {
    @Test
    fun `timeout is retryable without forced reload`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.TRANSFER_OWNER,
            ApiException(ApiFailureKind.TIMEOUT, cause = SocketTimeoutException("t"))
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `network IOException is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            IOException("offline")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
    }

    @Test
    fun `403 permission reloads and is not retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.REMOVE_MEMBER,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 403,
                serverMessage = "只有群主可以转让群主身份",
                serverCode = "NOT_GROUP_OWNER"
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("NOT_GROUP_OWNER", fb.serverCode)
    }

    @Test
    fun `member conflict forces reload`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverMessage = "already",
                serverCode = "ALREADY_MEMBER"
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertTrue(fb.shouldReload)
        assertFalse(fb.canRetry)
    }

    @Test
    fun `client validation 400 is fatal without retry`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 400,
                serverMessage = "禁言最长不能超过 30 天"
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_FATAL, fb.kind)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `server 503 is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ANNOUNCEMENT,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 503, serverMessage = "busy")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
    }

    @Test
    fun `success feedback never offers retry`() {
        val fb = GroupMutationFeedbackPolicy.success(GroupMutationAction.RENAME, "ok")
        assertEquals(GroupMutationFeedbackKind.SUCCESS, fb.kind)
        assertFalse(fb.canRetry)
    }

    @Test
    fun `contract permission code wins without http status`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.REMOVE_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_PERMISSION_DENIED")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_PERMISSION_DENIED", fb.serverCode)
    }

    @Test
    fun `contract conflict code wins over unrelated success status`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 200,
                serverCode = "GROUP_TARGET_NOT_MEMBER"
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
    }

    @Test
    fun `blank server code is ignored and falls back to status`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 403,
                serverMessage = "denied",
                serverCode = "  "
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertNull(fb.serverCode)
        assertEquals("denied", fb.detail)
    }

    @Test
    fun `http failure with unknown status is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ANNOUNCEMENT,
            ApiException(kind = ApiFailureKind.HTTP, serverMessage = "busy")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `null error is fatal without retry or reload`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.LOAD, null)
        assertEquals(GroupMutationFeedbackKind.ERROR_FATAL, fb.kind)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertNull(fb.detail)
    }

    @Test
    fun `401 without code is permission and reloads`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 401, serverMessage = "unauthorized")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertNull(fb.serverCode)
        assertEquals("unauthorized", fb.detail)
    }

    @Test
    fun `404 without code is conflict and reloads`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 404, serverMessage = "gone")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
    }

    @Test
    fun `422 client error is fatal without reload`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.RENAME,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 422, serverMessage = "bad name")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_FATAL, fb.kind)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `plain runtime exception is fatal with its message as detail`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.LOAD, RuntimeException("boom"))
        assertEquals(GroupMutationFeedbackKind.ERROR_FATAL, fb.kind)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertNull(fb.serverCode)
        assertEquals("boom", fb.detail)
    }

    @Test
    fun `network kind without status is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ANNOUNCEMENT,
            ApiException(kind = ApiFailureKind.NETWORK, serverMessage = "offline")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertEquals("offline", fb.detail)
    }

    @Test
    fun `410 gone is conflict and reloads`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.REMOVE_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 410, serverMessage = "gone")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
    }

    @Test
    fun `GROUP_NOT_FOUND code without status is conflict`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.LOAD,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_NOT_FOUND")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_NOT_FOUND", fb.serverCode)
    }

    @Test
    fun `GROUP_ACTOR_NOT_MEMBER code without status is permission`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_ACTOR_NOT_MEMBER")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_ACTOR_NOT_MEMBER", fb.serverCode)
    }

    @Test
    fun `invalid response kind without status is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.RENAME,
            ApiException(kind = ApiFailureKind.INVALID_RESPONSE, serverMessage = "garbled")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertEquals("garbled", fb.detail)
    }

    @Test
    fun `unexpected kind is fatal without retry`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            ApiException(kind = ApiFailureKind.UNEXPECTED, serverMessage = "weird")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_FATAL, fb.kind)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertEquals("weird", fb.detail)
    }

    @Test
    fun `GROUP_OWNER_PROTECTED code without status is permission`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.REMOVE_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_OWNER_PROTECTED")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_OWNER_PROTECTED", fb.serverCode)
    }

    @Test
    fun `GROUP_MEMBER_LIMIT_EXCEEDED code is conflict`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_MEMBER_LIMIT_EXCEEDED")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", fb.serverCode)
    }

    @Test
    fun `429 is fatal without retry or reload`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 429, serverMessage = "slow down")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_FATAL, fb.kind)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertEquals("slow down", fb.detail)
    }

    @Test
    fun `http 500 without message is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ANNOUNCEMENT,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 500)
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertNull(fb.serverCode)
    }

    @Test
    fun `unknown server code falls back to status permission`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.REMOVE_MEMBER,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 403,
                serverCode = "SOMETHING_ELSE"
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("SOMETHING_ELSE", fb.serverCode)
    }

    @Test
    fun `GROUP_ADMIN_PEER_PROTECTED code is permission`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.REMOVE_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_ADMIN_PEER_PROTECTED")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_ADMIN_PEER_PROTECTED", fb.serverCode)
    }

    @Test
    fun `GROUP_MEMBER_BLOCKED code is permission`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_MEMBER_BLOCKED")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_PERMISSION, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_MEMBER_BLOCKED", fb.serverCode)
    }

    @Test
    fun `GROUP_USER_NOT_FOUND code is conflict`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_USER_NOT_FOUND")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_USER_NOT_FOUND", fb.serverCode)
    }

    @Test
    fun `GROUP_OWNER_TRANSFER_REQUIRED code is conflict`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.TRANSFER_OWNER,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "GROUP_OWNER_TRANSFER_REQUIRED")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_OWNER_TRANSFER_REQUIRED", fb.serverCode)
    }

    @Test
    fun `success without detail carries no detail and no reload`() {
        val fb = GroupMutationFeedbackPolicy.success(GroupMutationAction.RENAME)
        assertEquals(GroupMutationFeedbackKind.SUCCESS, fb.kind)
        assertNull(fb.detail)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `502 bad gateway is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.MUTE,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 502, serverMessage = "bad gateway")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
        assertEquals("bad gateway", fb.detail)
    }

    @Test
    fun `504 gateway timeout is retryable`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ANNOUNCEMENT,
            ApiException(kind = ApiFailureKind.HTTP, statusCode = 504, serverMessage = "timeout")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, fb.kind)
        assertTrue(fb.canRetry)
        assertFalse(fb.shouldReload)
    }

    @Test
    fun `padded conflict code is trimmed and wins`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.LOAD,
            ApiException(kind = ApiFailureKind.HTTP, serverCode = "  GROUP_NOT_FOUND  ")
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("GROUP_NOT_FOUND", fb.serverCode)
    }

    @Test
    fun `unknown code with 404 falls back to conflict and keeps code`() {
        val fb = GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.ADD_MEMBER,
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 404,
                serverCode = "SOMETHING_ELSE"
            )
        )
        assertEquals(GroupMutationFeedbackKind.ERROR_CONFLICT, fb.kind)
        assertFalse(fb.canRetry)
        assertTrue(fb.shouldReload)
        assertEquals("SOMETHING_ELSE", fb.serverCode)
    }

    @Test
    fun `success with detail carries detail without retry or reload`() {
        val fb = GroupMutationFeedbackPolicy.success(GroupMutationAction.RENAME, "renamed")
        assertEquals(GroupMutationFeedbackKind.SUCCESS, fb.kind)
        assertEquals("renamed", fb.detail)
        assertFalse(fb.canRetry)
        assertFalse(fb.shouldReload)
    }
}
