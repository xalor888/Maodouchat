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

    @Test
    fun `presentMembers duplicate nickname keeps last nickname in map`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN", mutedUntil = 5L, groupNickname = "First"),
                GroupMemberDto(userId = "a", name = "A2", role = "MEMBER", mutedUntil = 9L, groupNickname = "Second"),
            ),
            currentUserId = "a",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(5L, state.myMutedUntil)
        assertEquals(mapOf("a" to "MEMBER"), state.memberRoleByUser)
        assertEquals(mapOf("a" to "Second"), state.memberNicknameByUser)
    }

    @Test
    fun `presentMembers member without explicit role defaults to MEMBER`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(GroupMemberDto(userId = "a", name = "A")),
            currentUserId = "me",
        )

        assertEquals(mapOf("a" to "MEMBER"), state.memberRoleByUser)
        assertEquals(null, state.myMemberRole)
    }

    @Test
    fun `presentGroup forces group flag even for a direct chat model`() {
        val state = controller.presentGroup(
            ChatDetailUiState(),
            Chat(id = "dm", isGroup = false),
            User("dm", "Dm"),
        )

        assertTrue(state.chatIsGroup)
        assertEquals("dm", state.chat?.id)
        assertFalse(state.isSecretChat == true)
    }

    @Test
    fun `presentMembers negative mute passes through without clamping`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(GroupMemberDto(userId = "me", name = "Me", role = "ADMIN", mutedUntil = -5L)),
            currentUserId = "me",
        )

        assertEquals(-5L, state.myMutedUntil)
    }

    @Test
    fun `resetDirectIdentityForGroup preserves chat and member fields`() {
        val group = Chat(id = "group", isGroup = true, groupName = "Team")
        val state = controller.resetDirectIdentityForGroup(
            ChatDetailUiState(
                chat = group,
                chatIsGroup = true,
                myMemberRole = "ADMIN",
                memberRoleByUser = mapOf("a" to "ADMIN"),
                identityWarning = "changed",
                safetyCode = "code",
            ),
        )

        assertEquals(group, state.chat)
        assertTrue(state.chatIsGroup)
        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(mapOf("a" to "ADMIN"), state.memberRoleByUser)
        assertEquals(null, state.identityWarning)
        assertEquals(null, state.safetyCode)
    }
    @Test
    fun `presentGroup keeps member fields while clearing secret fields`() {
        val state = controller.presentGroup(
            ChatDetailUiState(
                isSecretChat = true,
                disappearingMessageSeconds = 60,
                myMemberRole = "ADMIN",
                memberRoleByUser = mapOf("a" to "ADMIN"),
                memberNicknameByUser = mapOf("a" to "Al"),
            ),
            Chat(id = "group", isGroup = true, groupName = "Team"),
            User("group", "Team"),
        )

        assertFalse(state.isSecretChat == true)
        assertEquals(0, state.disappearingMessageSeconds)
        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(mapOf("a" to "ADMIN"), state.memberRoleByUser)
        assertEquals(mapOf("a" to "Al"), state.memberNicknameByUser)
    }

    @Test
    fun `presentMembers drops null nicknames from nickname map`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN"),
                GroupMemberDto(userId = "b", name = "B", role = "MEMBER", groupNickname = "Bee"),
            ),
            currentUserId = "me",
        )

        assertEquals(mapOf("b" to "Bee"), state.memberNicknameByUser)
        assertEquals(mapOf("a" to "ADMIN", "b" to "MEMBER"), state.memberRoleByUser)
    }

    @Test
    fun `presentMembers finds self even when self is not first`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "x", name = "X", role = "MEMBER"),
                GroupMemberDto(userId = "me", name = "Me", role = "ADMIN", mutedUntil = 7L),
            ),
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(7L, state.myMutedUntil)
        assertEquals(mapOf("x" to "MEMBER", "me" to "ADMIN"), state.memberRoleByUser)
    }

    @Test
    fun `resetDirectIdentityForGroup preserves group warning contact and chat`() {
        val group = Chat(id = "group", isGroup = true, groupName = "Team")
        val state = controller.resetDirectIdentityForGroup(
            ChatDetailUiState(
                chat = group,
                contact = User("group", "Team"),
                groupEncryptionWarning = "warn",
                identityWarning = "changed",
                safetyCode = "code",
            ),
        )

        assertEquals(group, state.chat)
        assertEquals("Team", state.contact?.name)
        assertEquals("warn", state.groupEncryptionWarning)
        assertEquals(null, state.identityWarning)
        assertEquals(null, state.safetyCode)
    }

    @Test
    fun `presentGroup replaces contact name even for the same chat id`() {
        val first = controller.presentGroup(
            ChatDetailUiState(),
            Chat(id = "group", isGroup = true, groupName = "Old"),
            User("group", "Old"),
        )
        val state = controller.presentGroup(
            first,
            Chat(id = "group", isGroup = true, groupName = "New"),
            User("group", "New"),
        )

        assertEquals("group", state.chat?.id)
        assertEquals("New", state.contact?.name)
    }


    @Test
    fun `presentMembers other member mute never leaks into self mute`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "me", name = "Me", role = "ADMIN"),
                GroupMemberDto(userId = "other", name = "Other", role = "MEMBER", mutedUntil = 999L),
            ),
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(0L, state.myMutedUntil)
        assertEquals(mapOf("me" to "ADMIN", "other" to "MEMBER"), state.memberRoleByUser)
    }

    @Test
    fun `presentMembers blank current user matches nobody`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN"),
                GroupMemberDto(userId = "b", name = "B", role = "MEMBER"),
            ),
            currentUserId = "",
        )

        assertEquals(null, state.myMemberRole)
        assertEquals(0L, state.myMutedUntil)
        assertEquals(mapOf("a" to "ADMIN", "b" to "MEMBER"), state.memberRoleByUser)
    }

    @Test
    fun `presentMembers finds self last among one hundred members`() {
        val members = (0 until 99).map { index ->
            GroupMemberDto(userId = "u$index", name = "U$index", role = "MEMBER")
        } + GroupMemberDto(userId = "me", name = "Me", role = "ADMIN", mutedUntil = 42L)
        val state = controller.presentMembers(
            ChatDetailUiState(),
            members,
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(42L, state.myMutedUntil)
        assertEquals(100, state.memberRoleByUser.size)
    }

    @Test
    fun `resetDirectIdentityForGroup on a fresh state stays cleared`() {
        val state = controller.resetDirectIdentityForGroup(ChatDetailUiState())

        assertEquals(null, state.identityWarning)
        assertEquals(null, state.safetyCode)
        assertFalse(state.canVerifyIdentity)
        assertFalse(state.isLoadingDeviceSafety)
        assertTrue(state.deviceSafetyStates.isEmpty())
        assertEquals(null, state.deviceSafetyWarning)
        assertEquals(IdentityTrustState.TRUSTED, state.trustState)
    }

    @Test
    fun `second presentGroup without warning keeps the first warning`() {
        val first = controller.presentGroup(
            ChatDetailUiState(),
            Chat(id = "group", isGroup = true, groupName = "Team"),
            User("group", "Team"),
            warning = "first",
        )
        val state = controller.presentGroup(
            first,
            Chat(id = "group", isGroup = true, groupName = "Renamed"),
            User("group", "Renamed"),
        )

        assertEquals("first", state.groupEncryptionWarning)
        assertEquals("Renamed", state.contact?.name)
        assertEquals("group", state.chat?.id)
    }

    @Test
    fun `presentMembers keeps surrounding whitespace in nicknames verbatim`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(
                GroupMemberDto(userId = "a", name = "A", role = "ADMIN"),
                GroupMemberDto(userId = "b", name = "B", role = "MEMBER", groupNickname = "  Bee  "),
            ),
            currentUserId = "me",
        )

        assertEquals(mapOf("b" to "  Bee  "), state.memberNicknameByUser)
    }

    @Test
    fun `presentGroup forces disappearing timer to zero from a negative value`() {
        val state = controller.presentGroup(
            ChatDetailUiState(disappearingMessageSeconds = -5),
            Chat(id = "group", isGroup = true, groupName = "Team"),
            User("group", "Team"),
        )

        assertEquals(0, state.disappearingMessageSeconds)
    }

    @Test
    fun `presentMembers with only self maps single role and empty nicknames`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(GroupMemberDto(userId = "me", name = "Me", role = "ADMIN", mutedUntil = 7L)),
            currentUserId = "me",
        )

        assertEquals("ADMIN", state.myMemberRole)
        assertEquals(7L, state.myMutedUntil)
        assertEquals(mapOf("me" to "ADMIN"), state.memberRoleByUser)
        assertTrue(state.memberNicknameByUser.isEmpty())
    }

    @Test
    fun `resetDirectIdentityForGroup clears trust state from changed`() {
        val state = controller.resetDirectIdentityForGroup(
            ChatDetailUiState(
                trustState = IdentityTrustState.CHANGED,
                deviceSafetyStates = listOf(
                    DeviceSafetyState(
                        deviceId = 1,
                        identityKey = "key",
                        identityFingerprint = "fp",
                        trustState = IdentityTrustState.CHANGED,
                        safetyCode = "code",
                    )
                ),
                deviceSafetyWarning = "warning",
            ),
        )

        assertEquals(IdentityTrustState.TRUSTED, state.trustState)
        assertTrue(state.deviceSafetyStates.isEmpty())
        assertEquals(null, state.deviceSafetyWarning)
    }

    @Test
    fun `presentMembers maps roles for members with empty names`() {
        val state = controller.presentMembers(
            ChatDetailUiState(),
            listOf(GroupMemberDto(userId = "x", name = "", role = "MEMBER")),
            currentUserId = "me",
        )

        assertEquals(mapOf("x" to "MEMBER"), state.memberRoleByUser)
        assertEquals(null, state.myMemberRole)
    }
}
