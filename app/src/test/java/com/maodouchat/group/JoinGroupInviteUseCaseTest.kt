package com.maodouchat.group

import com.maodouchat.contacts.QrScanFeedbackPolicy
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ChatDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class JoinGroupInviteUseCaseTest {

    @Test
    fun rejectsBlankInviteCode() {
        runBlocking {
            val useCase = JoinGroupInviteUseCase(
                tokenProvider = { "tok" },
                userIdProvider = { "u1" },
                sessionGate = { true },
                join = { _, _ -> error("should not call") },
            )
            val result = useCase.join("")
            assertIs<JoinGroupInviteResult.InvalidCode>(result)
        }
    }

    @Test
    fun rejectsMissingSession() {
        runBlocking {
            val useCase = JoinGroupInviteUseCase(
                tokenProvider = { "" },
                userIdProvider = { "u1" },
                sessionGate = { true },
                join = { _, _ -> error("should not call") },
            )
            val result = useCase.join("A".repeat(40))
            assertIs<JoinGroupInviteResult.Failed>(result)
            assertEquals(QrScanFeedbackPolicy.Kind.SESSION_EXPIRED, result.feedback.kind)
        }
    }

    @Test
    fun mapsJoinSuccess() {
        runBlocking {
            val chat = ChatDto(id = "g1", isGroup = true, groupName = "Team")
            val useCase = JoinGroupInviteUseCase(
                tokenProvider = { "tok" },
                userIdProvider = { "u1" },
                sessionGate = { true },
                join = { token, invite ->
                    assertEquals("tok", token)
                    assertEquals("A".repeat(40), invite)
                    Result.success(chat)
                },
            )
            val result = useCase.join("A".repeat(40))
            assertIs<JoinGroupInviteResult.Joined>(result)
            assertEquals("g1", result.chat.id)
        }
    }

    @Test
    fun mapsApiFailureThroughFeedbackPolicy() {
        runBlocking {
            val useCase = JoinGroupInviteUseCase(
                tokenProvider = { "tok" },
                userIdProvider = { "u1" },
                sessionGate = { true },
                join = { _, _ ->
                    Result.failure(
                        ApiException(
                            kind = ApiFailureKind.HTTP,
                            statusCode = 404,
                            serverCode = "GROUP_INVITE_EXPIRED",
                            serverMessage = "失效",
                        )
                    )
                },
            )
            val result = useCase.join("A".repeat(40))
            assertIs<JoinGroupInviteResult.Failed>(result)
            assertEquals(QrScanFeedbackPolicy.Kind.INVITE_INVALID_OR_EXPIRED, result.feedback.kind)
            assertTrue(
                result.feedback.serverCode == "GROUP_INVITE_EXPIRED" ||
                    result.feedback.kind == QrScanFeedbackPolicy.Kind.INVITE_INVALID_OR_EXPIRED,
            )
        }
    }

    @Test
    fun abortsWhenSessionGateFailsAfterJoin() {
        runBlocking {
            var gateOk = true
            val useCase = JoinGroupInviteUseCase(
                tokenProvider = { "tok" },
                userIdProvider = { "u1" },
                sessionGate = { gateOk },
                join = { _, _ ->
                    gateOk = false
                    Result.success(ChatDto(id = "g1", isGroup = true, groupName = "Team"))
                },
            )
            val result = useCase.join("A".repeat(40))
            assertIs<JoinGroupInviteResult.Aborted>(result)
        }
    }
}
