package com.maodouchat.domain.groups

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupInvitePolicyTest {

    @Test
    fun `defaults are seven days and one hundred uses`() {
        assertEquals(7L * 24L * 60L * 60L, GroupInvitePolicy.DEFAULT_EXPIRY_SECONDS)
        assertEquals(100, GroupInvitePolicy.DEFAULT_MAX_USES)
    }

    @Test
    fun `invite controller forwards policy defaults to gateway`() = runTest {
        var seen: Pair<Long, Int>? = null
        val gateway = FakeGroupNetworkGateway().apply {
            getOrCreateGroupInvite = { _, _, _, expires, uses ->
                seen = expires to uses
                Result.success(com.maodouchat.core.model.GroupInviteResponse(token = "tok", payload = "p"))
            }
        }
        GroupInviteController(tokenProvider = { "t" }, gateway = gateway).fetchInvite("c1")

        assertEquals(GroupInvitePolicy.DEFAULT_EXPIRY_SECONDS to GroupInvitePolicy.DEFAULT_MAX_USES, seen)
    }
}
