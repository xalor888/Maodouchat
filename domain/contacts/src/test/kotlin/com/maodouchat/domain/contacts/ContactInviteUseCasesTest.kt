package com.maodouchat.domain.contacts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactInviteUseCasesTest {

    @Test
    fun contactMutationContractDelegatesToFake() {
        runBlocking {
            var renamed: String? = null
            val fake = object : ContactMutationUseCase {
                override suspend fun setNickname(userId: String, nickname: String): Result<Unit> {
                    renamed = "$userId:$nickname"
                    return Result.success(Unit)
                }

                override suspend fun removeFriend(userId: String): Result<Unit> =
                    Result.success(Unit)

                override suspend fun blockUser(userId: String): Result<Unit> =
                    Result.success(Unit)

                override suspend fun unblockUser(userId: String): Result<Unit> =
                    Result.success(Unit)
            }
            assertTrue(fake.setNickname("u1", "Ada").isSuccess)
            assertEquals("u1:Ada", renamed)
        }
    }
}
