package com.maodouchat.chatdetail

import com.maodouchat.crypto.DeviceSafetyState
import com.maodouchat.crypto.IdentityTrustState
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
    fun `presentGroup without warning keeps existing groupEncryptionWarning`() {
        val group = Chat(id = "group", isGroup = true, groupName = "Team")
        val state = controller.presentGroup(
            ChatDetailUiState(groupEncryptionWarning = "previous"),
            group,
            User("group", "Team"),
            warning = null,
        )

        assertEquals("previous", state.groupEncryptionWarning)
        assertEquals("Team", state.contact?.name)
        assertFalse(state.isSecretChat == true)
    }

    @Test
    fun `presentMembers without self yields null role and full role map`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN"),
                GroupMemberDto(userId = "b", name = "B", role = "MEMBER"),
            ),
            currentUserId = "me",
        )

        assertEquals(null, state.myMemberRole)
        assertEquals(0L, state.myMutedUntil)
        assertEquals(mapOf("a" to "ADMIN", "b" to "MEMBER"), state.memberRoleByUser)
        assertTrue(state.memberNicknameByUser.isEmpty())
    }

    @Test
    fun `presentMembers drops blank nicknames from nickname map`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN", groupNickname = "   "),
                GroupMemberDto(userId = "b", name = "B", role = "MEMBER", groupNickname = "Bee"),
            ),
            currentUserId = "a",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(mapOf("b" to "Bee"), state.memberNicknameByUser)
    }

    @Test
    fun `presentGroup always clears secret chat fields for groups`() {
        val group = Chat(id = "group", isGroup = true)
        val state = controller.presentGroup(
            ChatDetailUiState(isSecretChat = true, disappearingMessageSeconds = 86400),
            group,
            User("group", "Team"),
        )

        assertFalse(state.isSecretChat == true)
        assertEquals(0, state.disappearingMessageSeconds)
        assertTrue(state.chatIsGroup)
    }

    @Test
    fun `presentGroup without warning on fresh state leaves warning null`() {
        val group = Chat(id = "group", isGroup = true, groupName = "Team")
        val state = controller.presentGroup(
            ChatDetailUiState(),
            group,
            User("group", "Team"),
        )

        assertEquals(group, state.chat)
        assertEquals(null, state.groupEncryptionWarning)
        assertTrue(state.chatIsGroup)
        assertFalse(state.isSecretChat == true)
    }

    @Test
    fun `presentGroup replaces previous chat and contact`() {
        val first = controller.presentGroup(
            ChatDetailUiState(),
            Chat(id = "old", isGroup = true, groupName = "Old"),
            User("old", "Old"),
            warning = "old warning",
        )

        val state = controller.presentGroup(
            first,
            Chat(id = "new", isGroup = true, groupName = "New"),
            User("new", "New"),
            warning = "new warning",
        )

        assertEquals("new", state.chat?.id)
        assertEquals("New", state.contact?.name)
        assertEquals("new warning", state.groupEncryptionWarning)
    }

    @Test
    fun `presentMembers with empty list clears maps and self fields`() {
        val state = controller.presentMembers(
            ChatDetailUiState(
                memberRoleByUser = mapOf("x" to "ADMIN"),
                memberNicknameByUser = mapOf("x" to "X"),
            ),
            emptyList(),
            currentUserId = "me",
        )

        assertEquals(null, state.myMemberRole)
        assertEquals(0L, state.myMutedUntil)
        assertTrue(state.memberRoleByUser.isEmpty())
        assertTrue(state.memberNicknameByUser.isEmpty())
    }

    @Test
    fun `presentMembers self without explicit mute defaults to zero`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(GroupMemberDto(userId = "me", name = "Me", role = "ADMIN")),
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(0L, state.myMutedUntil)
    }

    @Test
    fun `presentMembers drops blank nickname for self but keeps self role`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(GroupMemberDto(userId = "me", name = "Me", role = "ADMIN", groupNickname = "   ")),
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertTrue(state.memberNicknameByUser.isEmpty())
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
        assertEquals(IdentityTrustState.TRUSTED, state.trustState)
    }


    @Test
    fun `presentMembers duplicate userId keeps first self fields but last role in map`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN", mutedUntil = 5L),
                GroupMemberDto(userId = "a", name = "A2", role = "MEMBER", mutedUntil = 9L),
            ),
            currentUserId = "a",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(5L, state.myMutedUntil)
        assertEquals(mapOf("a" to "MEMBER"), state.memberRoleByUser)
    }

    @Test
    fun `presentGroup empty warning string replaces existing warning`() {
        val state = controller.presentGroup(
            ChatDetailUiState(groupEncryptionWarning = "previous"),
            Chat(id = "group", isGroup = true, groupName = "Team"),
            User("group", "Team"),
            warning = "",
        )

        assertEquals("", state.groupEncryptionWarning)
    }

    @Test
    fun `resetDirectIdentityForGroup clears device safety list and warning`() {
        val state = controller.resetDirectIdentityForGroup(
            ChatDetailUiState(
                deviceSafetyStates = listOf(
                    DeviceSafetyState(1, "key", "fp", IdentityTrustState.TRUSTED, "code"),
                ),
                deviceSafetyWarning = "error",
            ),
        )

        assertTrue(state.deviceSafetyStates.isEmpty())
        assertEquals(null, state.deviceSafetyWarning)
    }

    @Test
    fun `presentGroup preserves unrelated member fields`() {
        val state = controller.presentGroup(
            ChatDetailUiState(
                myMemberRole = "ADMIN",
                memberRoleByUser = mapOf("a" to "ADMIN"),
                memberNicknameByUser = mapOf("a" to "Al"),
            ),
            Chat(id = "group", isGroup = true, groupName = "Team"),
            User("group", "Team"),
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(mapOf("a" to "ADMIN"), state.memberRoleByUser)
        assertEquals(mapOf("a" to "Al"), state.memberNicknameByUser)
    }

    @Test
    fun `presentMembers preserves chat and group warning fields`() {
        val group = Chat(id = "group", isGroup = true, groupName = "Team")
        val state = controller.presentMembers(
            ChatDetailUiState(chat = group, groupEncryptionWarning = "warn"),
            listOf(GroupMemberDto(userId = "a", name = "A", role = "MEMBER")),
            currentUserId = "me",
        )

        assertEquals(group, state.chat)
        assertEquals("warn", state.groupEncryptionWarning)
    }

}
