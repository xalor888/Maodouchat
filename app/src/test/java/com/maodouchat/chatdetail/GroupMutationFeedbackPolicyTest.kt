package com.maodouchat.chatdetail

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.ui.screen.chatdetail.GroupMutationAction
import com.maodouchat.ui.screen.chatdetail.GroupMutationFeedbackKind
import com.maodouchat.ui.screen.chatdetail.GroupMutationFeedbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
