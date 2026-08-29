package com.maodouchat.chatdetail

import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.network.GroupMemberDto
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.ChatGroupSecurityStateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGroupSecurityStateControllerTest {
    private val controller = ChatGroupSecurityStateController()

    @Test
    fun `present group preserves stable group UI fields`() {
        val group = Chat(id = "group", isGroup = true, groupName = "Team")
        val state = controller.presentGroup(
            ChatDetailUiState(isSecretChat = true, disappearingMessageSeconds = 60),
            group,
            User("group", "Team"),
            warning = "members changed",
        )

        assertEquals(group, state.chat)
        assertTrue(state.chatIsGroup)
        assertFalse(state.isSecretChat == true)
        assertEquals(0, state.disappearingMessageSeconds)
        assertEquals("members changed", state.groupEncryptionWarning)
    }

    @Test
    fun `present members maps role mute and nickname state`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "me", name = "Me", role = "ADMIN", mutedUntil = 123L),
                GroupMemberDto(userId = "other", name = "Other", role = "MEMBER", groupNickname = "Alias"),
            ),
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(123L, state.myMutedUntil)
        assertEquals(mapOf("me" to "ADMIN", "other" to "MEMBER"), state.memberRoleByUser)
        assertEquals(mapOf("other" to "Alias"), state.memberNicknameByUser)
    }

    @Test
    fun `group identity reset removes direct chat safety details`() {
        val state = controller.resetDirectIdentityForGroup(
            ChatDetailUiState(
                identityWarning = "changed",
                safetyCode = "code",
                canVerifyIdentity = true,
                isLoadingDeviceSafety = true,
                deviceSafetyWarning = "error",
            ),
        )

        assertEquals(null, state.identityWarning)
        assertEquals(null, state.safetyCode)
        assertFalse(state.canVerifyIdentity)
        assertFalse(state.isLoadingDeviceSafety)
        assertEquals(SignalProtocol.IdentityTrustState.TRUSTED, state.trustState)
    }
}
