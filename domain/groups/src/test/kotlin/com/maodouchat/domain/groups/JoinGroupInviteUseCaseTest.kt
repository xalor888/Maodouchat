package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.JoinGroupInviteResult
import com.maodouchat.core.model.Kind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JoinGroupInviteUseCaseTest {

    @Test
    fun rejectsBlankInviteCode() {
        runBlocking {
            val useCase = joinUseCase(join = { _, _ -> error("should not call") })
            assertIs<JoinGroupInviteResult.InvalidCode>(useCase.join(""))
        }
    }
    @Test
    fun rejectsMissingSession() {
        runBlocking {
            val useCase = joinUseCase(token = "", join = { _, _ -> error("should not call") })
            val result = useCase.join("A".repeat(40))
            assertIs<JoinGroupInviteResult.Failed>(result)
            assertEquals(Kind.SESSION_EXPIRED, result.feedback.kind)
        }
    }
    @Test
    fun mapsJoinSuccess() {
        runBlocking {
            val chat = ChatDto(id = "g1", isGroup = true, groupName = "Team")
            val useCase = joinUseCase(join = { token, invite ->
                assertEquals("tok", token)
                assertEquals("A".repeat(40), invite)
                Result.success(chat)
            })
            val result = useCase.join("A".repeat(40))
            assertIs<JoinGroupInviteResult.Joined>(result)
            assertEquals("g1", result.chat.id)
        }
    }
    @Test
    fun mapsJoinThrowToFailed() {
        runBlocking {
            val useCase = joinUseCase(join = { _, _ -> throw IllegalStateException("boom") })
            assertIs<JoinGroupInviteResult.Failed>(useCase.join("A".repeat(40)))
        }
    }
    @Test
    fun abortsWhenSessionDropsAfterSuccess() {
        runBlocking {
            val chat = ChatDto(id = "g1", isGroup = true)
            var calls = 0
            val useCase = JoinGroupInviteUseCase(
                tokenProvider = { "tok" },
                userIdProvider = { "u1" },
                sessionGate = { ++calls < 2 },
                join = { _, _ -> Result.success(chat) },
            )
            assertIs<JoinGroupInviteResult.Aborted>(useCase.join("A".repeat(40)))
        }
    }

    private fun joinUseCase(
        token: String = "tok",
        join: suspend (String, String) -> Result<ChatDto>,
    ): JoinGroupInviteUseCase = JoinGroupInviteUseCase(
        tokenProvider = { token },
        userIdProvider = { "u1" },
        sessionGate = { true },
        join = join,
    )
}
