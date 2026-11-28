package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.DeviceSafetyState
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.IdentityTrustState
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr205a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit205a(b: Boolean): String = if (b) "1" else "0"

private fun opt205a(s: String?): String = s?.let { encStr205a(it) } ?: "~"

private data class SecChat205a(
    val id: String,
    val isGroup: Boolean,
    val chatType: String,
    val groupName: String?,
    val disappearing: Int,
)

private data class SecContact205a(
    val id: String,
    val name: String,
    val nickname: String?,
)

private data class SecMember205a(
    val userId: String,
    val role: String,
    val nickname: String?,
    val mutedUntil: Long,
)

private data class SecDevice205a(
    val deviceId: Int,
    val key: String,
    val fingerprint: String,
    val trust: String,
    val safetyCode: String?,
    val isCurrent: Boolean,
)

// op kind: 0 presentGroup，1 presentMembers，2 resetDirectIdentityForGroup。
private data class SecOp205a(
    val kind: Int,
    val chat: SecChat205a = SecChat205a("g1", true, "GROUP", "Team", 0),
    val contact: SecContact205a = SecContact205a("g1", "Team", null),
    val warning: String? = null,
    val members: List<SecMember205a> = emptyList(),
    val currentUserId: String = "me",
)

private data class SecCase205a(
    val initHasChat: Boolean = false,
    val initChat: SecChat205a = SecChat205a("g0", true, "GROUP", "Old", 0),
    val initChatIsGroup: Boolean = false,
    // 0 null，1 false，2 true。
    val initIsSecret: Int = 0,
    val initContact: SecContact205a = SecContact205a("u0", "Nobody", null),
    val initWarn: String? = null,
    val initDisappearing: Int = 0,
    val initMyRole: String? = null,
    val initMyMuted: Long = 0L,
    val initRoleMap: List<Pair<String, String>> = emptyList(),
    val initNickMap: List<Pair<String, String>> = emptyList(),
    val initIdentityWarning: String? = null,
    val initSafetyCode: String? = null,
    val initTrust: String = "UNKNOWN",
    val initDevices: List<SecDevice205a> = emptyList(),
    val initLoadingSafety: Boolean = false,
    val initSafetyWarning: String? = null,
    val initCanVerify: Boolean = false,
    val ops: List<SecOp205a> = emptyList(),
)

private fun secChat205a(c: SecChat205a): Chat = Chat(
    id = c.id,
    lastMessageTime = 0L,
    isGroup = c.isGroup,
    chatType = c.chatType,
    groupName = c.groupName,
    disappearingMessageSeconds = c.disappearing,
)

private fun secInitialState205a(c: SecCase205a): ChatDetailUiState = ChatDetailUiState(
    contact = User(c.initContact.id, c.initContact.name, nickname = c.initContact.nickname),
    chat = if (c.initHasChat) secChat205a(c.initChat) else null,
    chatIsGroup = c.initChatIsGroup,
    isSecretChat = when (c.initIsSecret) {
        1 -> false
        2 -> true
        else -> null
    },
    groupEncryptionWarning = c.initWarn,
    disappearingMessageSeconds = c.initDisappearing,
    myMemberRole = c.initMyRole,
    myMutedUntil = c.initMyMuted,
    memberRoleByUser = c.initRoleMap.toMap(),
    memberNicknameByUser = c.initNickMap.toMap(),
    identityWarning = c.initIdentityWarning,
    safetyCode = c.initSafetyCode,
    trustState = IdentityTrustState.valueOf(c.initTrust),
    deviceSafetyStates = c.initDevices.map {
        DeviceSafetyState(it.deviceId, it.key, it.fingerprint, IdentityTrustState.valueOf(it.trust), it.safetyCode, it.isCurrent)
    },
    isLoadingDeviceSafety = c.initLoadingSafety,
    deviceSafetyWarning = c.initSafetyWarning,
    canVerifyIdentity = c.initCanVerify,
)

private fun encSecChat205a(chat: Chat?): String = chat?.let {
    listOf(
        encStr205a(it.id),
        bit205a(it.isGroup),
        encStr205a(it.chatType),
        opt205a(it.groupName),
        it.disappearingMessageSeconds.toString(),
    ).joinToString(":")
} ?: "~"

private fun encSecMap205a(m: Map<String, String>): String =
    m.entries.joinToString(";") { encStr205a(it.key) + "=" + encStr205a(it.value) }

private fun encSecDevices205a(devices: List<DeviceSafetyState>): String =
    devices.joinToString(";") {
        listOf(
            it.deviceId.toString(),
            encStr205a(it.identityKey),
            encStr205a(it.identityFingerprint),
            it.trustState.name,
            opt205a(it.safetyCode),
            bit205a(it.isCurrent),
        ).joinToString(":")
    }

private fun encSecState205a(s: ChatDetailUiState): String = listOf(
    encSecChat205a(s.chat),
    bit205a(s.chatIsGroup),
    when (s.isSecretChat) {
        null -> "~"
        true -> "1"
        false -> "0"
    },
    encStr205a(s.contact.id) + ":" + encStr205a(s.contact.name) + ":" + opt205a(s.contact.nickname),
    opt205a(s.groupEncryptionWarning),
    s.disappearingMessageSeconds.toString(),
    opt205a(s.myMemberRole),
    s.myMutedUntil.toString(),
    encSecMap205a(s.memberRoleByUser),
    encSecMap205a(s.memberNicknameByUser),
    opt205a(s.identityWarning),
    opt205a(s.safetyCode),
    s.trustState.name,
    encSecDevices205a(s.deviceSafetyStates),
    bit205a(s.isLoadingDeviceSafety),
    opt205a(s.deviceSafetyWarning),
    bit205a(s.canVerifyIdentity),
).joinToString("|")

private fun secOracle205a(c: SecCase205a): String {
    var live = secInitialState205a(c)
    val effects = mutableListOf<String>()
    fun snap(prefix: String) {
        effects += prefix + ":" + encSecState205a(live)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                live = live.copy(
                    chat = secChat205a(op.chat),
                    chatIsGroup = true,
                    isSecretChat = false,
                    contact = User(op.contact.id, op.contact.name, nickname = op.contact.nickname),
                    groupEncryptionWarning = op.warning ?: live.groupEncryptionWarning,
                    disappearingMessageSeconds = 0,
                )
            }
            1 -> {
                val members = op.members.map {
                    GroupMemberDto(it.userId, it.userId, role = it.role, groupNickname = it.nickname, mutedUntil = it.mutedUntil)
                }
                val selfMember = members.firstOrNull { it.userId == op.currentUserId }
                live = live.copy(
                    myMemberRole = selfMember?.role,
                    myMutedUntil = selfMember?.mutedUntil ?: 0L,
                    memberRoleByUser = members.associate { it.userId to it.role },
                    memberNicknameByUser = members
                        .filter { !it.groupNickname.isNullOrBlank() }
                        .associate { it.userId to it.groupNickname!! },
                )
            }
            2 -> {
                live = live.copy(
                    identityWarning = null,
                    safetyCode = null,
                    trustState = IdentityTrustState.TRUSTED,
                    deviceSafetyStates = emptyList(),
                    isLoadingDeviceSafety = false,
                    deviceSafetyWarning = null,
                    canVerifyIdentity = false,
                )
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun secProd205a(c: SecCase205a): String {
    val controller = ChatGroupSecurityStateController()
    var live = secInitialState205a(c)
    val effects = mutableListOf<String>()
    fun snap(prefix: String) {
        effects += prefix + ":" + encSecState205a(live)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> live = controller.presentGroup(
                live,
                secChat205a(op.chat),
                User(op.contact.id, op.contact.name, nickname = op.contact.nickname),
                op.warning,
            )
            1 -> live = controller.presentMembers(
                live,
                op.members.map {
                    GroupMemberDto(it.userId, it.userId, role = it.role, groupNickname = it.nickname, mutedUntil = it.mutedUntil)
                },
                op.currentUserId,
            )
            2 -> live = controller.resetDirectIdentityForGroup(live)
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val SEC_IDS205a = listOf("", " ", "u1", "u2", "me", "g1", "a", "x\ty", "ünïcödé")
private val SEC_ROLES205a = listOf("OWNER", "ADMIN", "MEMBER", "", "owner")
private val SEC_CHAT_TYPES205a = listOf("GROUP", "DIRECT", "SECRET", "CHANNEL", "")
private val SEC_TRUSTS205a = listOf("UNKNOWN", "TRUSTED", "VERIFIED", "CHANGED")
private val SEC_NICKS205a: List<String?> = listOf(null, "", " ", "Al", "  Bee  ", "ünïcödé")

private fun randomSecMember205a(r: Random): SecMember205a = SecMember205a(
    userId = SEC_IDS205a[r.nextInt(SEC_IDS205a.size)],
    role = SEC_ROLES205a[r.nextInt(SEC_ROLES205a.size)],
    nickname = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
    mutedUntil = listOf(0L, 5L, -5L, 123L, 999L)[r.nextInt(5)],
)

private fun randomSecChat205a(r: Random): SecChat205a = SecChat205a(
    id = SEC_IDS205a[r.nextInt(SEC_IDS205a.size)],
    isGroup = r.nextBoolean(),
    chatType = SEC_CHAT_TYPES205a[r.nextInt(SEC_CHAT_TYPES205a.size)],
    groupName = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
    disappearing = listOf(0, 60, -5, 86400)[r.nextInt(4)],
)

private fun randomSecOp205a(r: Random): SecOp205a {
    return when (r.nextInt(3)) {
        0 -> SecOp205a(
            kind = 0,
            chat = randomSecChat205a(r),
            contact = SecContact205a(
                SEC_IDS205a[r.nextInt(SEC_IDS205a.size)],
                listOf("", "Team", "  Team  ", "ünïcödé")[r.nextInt(4)],
                SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
            ),
            warning = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
        )
        1 -> SecOp205a(
            kind = 1,
            members = List(r.nextInt(0, 5)) { randomSecMember205a(r) },
            currentUserId = SEC_IDS205a[r.nextInt(SEC_IDS205a.size)],
        )
        else -> SecOp205a(kind = 2)
    }
}

private fun randomSecCase205a(r: Random): SecCase205a {
    return SecCase205a(
        initHasChat = r.nextInt(4) != 0,
        initChat = randomSecChat205a(r),
        initChatIsGroup = r.nextBoolean(),
        initIsSecret = r.nextInt(3),
        initContact = SecContact205a(
            SEC_IDS205a[r.nextInt(SEC_IDS205a.size)],
            listOf("", "Nobody", "Team")[r.nextInt(3)],
            SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
        ),
        initWarn = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
        initDisappearing = listOf(0, 60, -5, 86400)[r.nextInt(4)],
        initMyRole = if (r.nextInt(3) == 0) SEC_ROLES205a[r.nextInt(SEC_ROLES205a.size)] else null,
        initMyMuted = listOf(0L, 7L, -5L)[r.nextInt(3)],
        initRoleMap = List(r.nextInt(0, 3)) {
            SEC_IDS205a[r.nextInt(SEC_IDS205a.size)] to SEC_ROLES205a[r.nextInt(SEC_ROLES205a.size)]
        },
        initNickMap = List(r.nextInt(0, 3)) {
            SEC_IDS205a[r.nextInt(SEC_IDS205a.size)] to listOf("Al", "Bee", "  Bee  ")[r.nextInt(3)]
        },
        initIdentityWarning = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
        initSafetyCode = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
        initTrust = SEC_TRUSTS205a[r.nextInt(SEC_TRUSTS205a.size)],
        initDevices = List(r.nextInt(0, 3)) {
            SecDevice205a(
                deviceId = r.nextInt(0, 3),
                key = listOf("key", "", "k2")[r.nextInt(3)],
                fingerprint = listOf("fp", "", "f2")[r.nextInt(3)],
                trust = SEC_TRUSTS205a[r.nextInt(SEC_TRUSTS205a.size)],
                safetyCode = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
                isCurrent = r.nextBoolean(),
            )
        },
        initLoadingSafety = r.nextBoolean(),
        initSafetyWarning = SEC_NICKS205a[r.nextInt(SEC_NICKS205a.size)],
        initCanVerify = r.nextBoolean(),
        ops = List(r.nextInt(0, 9)) { randomSecOp205a(r) },
    )
}

private fun boundarySecCase205a(r: Random): SecCase205a {
    val base = SecCase205a()
    val group = SecChat205a("group", true, "GROUP", "Team", 0)
    val cases = listOf(
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", null), warning = "members changed"))),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", null), warning = ""))),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", null), warning = null)), initWarn = "previous"),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = SecChat205a("dm", false, "DIRECT", null, 0), contact = SecContact205a("dm", "Dm", null)))),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = SecChat205a("s1", false, "SECRET", null, 0), contact = SecContact205a("s1", "Secret", null))), initIsSecret = 2, initDisappearing = 60),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "", null)))),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", "Al")))),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", null), warning = "first"), SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Renamed", null)))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("me", "ADMIN", null, 123L), SecMember205a("other", "MEMBER", "Alias", 0L)), currentUserId = "me"))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("a", "ADMIN", "   ", 0L), SecMember205a("b", "MEMBER", "Bee", 0L)), currentUserId = "a"))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("a", "ADMIN", null, 5L), SecMember205a("a", "MEMBER", "Second", 9L)), currentUserId = "a"))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("a", "ADMIN", "First", 5L), SecMember205a("a", "MEMBER", "Second", 9L)), currentUserId = "a"))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = emptyList(), currentUserId = "me")), initRoleMap = listOf("x" to "ADMIN"), initNickMap = listOf("x" to "X")),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("me", "ADMIN", null, -5L)), currentUserId = "me"))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("a", "ADMIN", null, 0L), SecMember205a("b", "MEMBER", null, 0L)), currentUserId = ""))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = List(99) { SecMember205a("u$it", "MEMBER", null, 0L) } + SecMember205a("me", "ADMIN", null, 42L), currentUserId = "me"))),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("x", "MEMBER", null, 0L)), currentUserId = "me"))),
        base.copy(ops = listOf(SecOp205a(kind = 2)), initIdentityWarning = "changed", initSafetyCode = "code", initCanVerify = true, initLoadingSafety = true, initSafetyWarning = "error"),
        base.copy(ops = listOf(SecOp205a(kind = 2)), initTrust = "CHANGED", initDevices = listOf(SecDevice205a(1, "key", "fp", "CHANGED", "code", false)), initSafetyWarning = "warning"),
        base.copy(ops = listOf(SecOp205a(kind = 2)), initHasChat = true, initChatIsGroup = true, initMyRole = "ADMIN", initRoleMap = listOf("a" to "ADMIN"), initWarn = "warn"),
        base.copy(ops = listOf(SecOp205a(kind = 2))),
        base.copy(ops = listOf(SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", null)), SecOp205a(kind = 1, members = listOf(SecMember205a("me", "ADMIN", "Al", 7L)), currentUserId = "me"), SecOp205a(kind = 2))),
        base.copy(ops = listOf(SecOp205a(kind = 2), SecOp205a(kind = 1, members = listOf(SecMember205a("me", "ADMIN", null, 3L)), currentUserId = "me"), SecOp205a(kind = 0, chat = group, contact = SecContact205a("group", "Team", null), warning = "w"))),
        base.copy(ops = emptyList()),
        base.copy(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("b", "MEMBER", "  Bee  ", 0L)), currentUserId = "me"))),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatGroupSecurityStateControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(205301)
        repeat(300) {
            val c = randomSecCase205a(r)
            assertEquals(secOracle205a(c), secProd205a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(205302)
        repeat(200) {
            val c = boundarySecCase205a(r)
            assertEquals(secOracle205a(c), secProd205a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            SecCase205a(ops = listOf(SecOp205a(kind = 0, chat = SecChat205a("group", true, "GROUP", "Team", 0), contact = SecContact205a("group", "Team", null), warning = "members changed"))),
            SecCase205a(ops = listOf(SecOp205a(kind = 0, chat = SecChat205a("group", true, "GROUP", "Team", 0), contact = SecContact205a("group", "Team", null), warning = null)), initWarn = "previous"),
            SecCase205a(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("me", "ADMIN", null, 123L), SecMember205a("other", "MEMBER", "Alias", 0L)), currentUserId = "me"))),
            SecCase205a(ops = listOf(SecOp205a(kind = 1, members = listOf(SecMember205a("a", "ADMIN", null, 5L), SecMember205a("a", "MEMBER", "Second", 9L)), currentUserId = "a"))),
            SecCase205a(ops = listOf(SecOp205a(kind = 1, members = emptyList(), currentUserId = "me"))),
            SecCase205a(ops = listOf(SecOp205a(kind = 2)), initIdentityWarning = "changed", initSafetyCode = "code", initTrust = "CHANGED"),
            SecCase205a(ops = listOf(SecOp205a(kind = 2)), initDevices = listOf(SecDevice205a(1, "key", "fp", "TRUSTED", "code", true))),
            SecCase205a(ops = listOf(SecOp205a(kind = 0, chat = SecChat205a("group", true, "GROUP", "Team", 0), contact = SecContact205a("group", "Team", null)), SecOp205a(kind = 1, members = listOf(SecMember205a("me", "ADMIN", "Al", 7L)), currentUserId = "me"), SecOp205a(kind = 2))),
            SecCase205a(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(secOracle205a(c), secProd205a(c))
        }
    }
}
