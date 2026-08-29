package com.maodouchat.server.plugins

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupMutationHttpPolicyTest {
    @Test
    fun `successful mutation has no error response`() {
        assertNull(GroupMutationHttpPolicy.response(com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED))
    }

    @Test
    fun `permission failures keep stable machine readable codes`() {
        val cases = listOf(
            com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN to
                (HttpStatusCode.Forbidden to "GROUP_PERMISSION_DENIED"),
            com.maodouchat.server.repository.GroupMemberMutationResult.OWNER_PROTECTED to
                (HttpStatusCode.Forbidden to "GROUP_OWNER_PROTECTED"),
            com.maodouchat.server.repository.GroupMemberMutationResult.PEER_ADMIN_PROTECTED to
                (HttpStatusCode.Forbidden to "GROUP_ADMIN_PEER_PROTECTED"),
            com.maodouchat.server.repository.GroupMemberMutationResult.SELF_NOT_ALLOWED to
                (HttpStatusCode.BadRequest to "GROUP_SELF_ACTION_NOT_ALLOWED"),
        )

        cases.forEach { (result, expected) ->
            val mapped = GroupMutationHttpPolicy.response(result)
            requireNotNull(mapped)
            assertEquals(expected.first, mapped.first)
            assertEquals(expected.second, mapped.second.code)
        }
    }

    @Test
    fun `not found and conflict outcomes map to resource semantics`() {
        val notFound = GroupMutationHttpPolicy.response(
            com.maodouchat.server.repository.GroupMemberMutationResult.CHAT_NOT_FOUND,
        )
        requireNotNull(notFound)
        assertEquals(HttpStatusCode.NotFound, notFound.first)
        assertEquals("GROUP_NOT_FOUND", notFound.second.code)

        val limit = GroupMutationHttpPolicy.response(
            com.maodouchat.server.repository.GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED,
        )
        requireNotNull(limit)
        assertEquals(HttpStatusCode.Conflict, limit.first)
        assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", limit.second.code)
    }
}
