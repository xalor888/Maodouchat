package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.core.model.User
import com.maodouchat.core.model.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr210b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit210b(b: Boolean): String = if (b) "1" else "0"

private fun opt210b(s: String?): String = s?.let { encStr210b(it) } ?: "~"

private fun tri210b(v: Int): Boolean? = when (v) {
    0 -> false
    1 -> true
    else -> null
}

// op kind：0 updateChatSettings，1 deleteChat，2 startSecretChatWithPeer，
// 3 clearCreatedSecretChat。
// remoteMode（按 kind 解释）：updateSettings/delete/create 均为 0 成功、1 失败 RT 带消息、
// 2 失败 RT 空消息、3 失败 CE；delete 另有 4 失败 ApiException 409
// GROUP_OWNER_TRANSFER_REQUIRED、5 失败 ApiException 403；其余另有 4 失败 ApiException 500。
// cacheFault：0 全成功、1 第 1 次 cache 抛 RT、2 第 2 次 cache 抛 RT、
// 3 第 1 次 cache 抛 CE、4 第 2 次 cache 抛 CE。
// cleanupMode：0 成功、1 抛 RT 带消息、2 抛 RT 空消息、3 抛 CE。
private data class MutOp210b(
    val kind: Int,
    val chatIdx: Int = 0,
    val optPin: Long = 0,
    val optMuted: Boolean = false,
    val optArch: Boolean = false,
    val optMarked: Boolean = false,
    val reqPin: Int = -1,
    val reqMuted: Int = -1,
    val reqArch: Int = -1,
    val reqMarked: Int = -1,
    val remoteMode: Int = 0,
    val setPin: Long = 0,
    val setMuted: Boolean = false,
    val setArch: Boolean = false,
    val setMarked: Boolean = false,
    val setUpd: Long = 0,
    val cacheFault: Int = 0,
    val chatId: String = "",
    val peerId: String = "",
    val dtoId: String = "",
    val dtoIsGroup: Boolean = false,
    val dtoChatType: String = "SECRET",
    val dtoLastMsgType: String = "TEXT",
    val dtoParts: List<Pair<String, String>> = emptyList(),
    val cleanupMode: Int = 0,
    val sessionGen: Long = 1,
)

private data class MutChat210b(
    val id: String,
    val pinnedAt: Long = 0,
    val notificationsMuted: Boolean = false,
    val archived: Boolean = false,
    val markedUnread: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessageTime: Long = 0,
    val settingsUpdatedAt: Long = 0,
    val isGroup: Boolean = false,
    val chatType: String = "DIRECT",
    val groupName: String? = null,
)

private fun MutChat210b.toChat(): Chat = Chat(
    id = id,
    pinnedAt = pinnedAt,
    notificationsMuted = notificationsMuted,
    archived = archived,
    markedUnread = markedUnread,
    unreadCount = unreadCount,
    lastMessageTime = lastMessageTime,
    settingsUpdatedAt = settingsUpdatedAt,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
)

private fun mutChatEnc210b(c: MutChat210b): String = listOf(
    encStr210b(c.id),
    c.pinnedAt.toString(),
    bit210b(c.notificationsMuted),
    bit210b(c.archived),
    bit210b(c.markedUnread),
    c.unreadCount.toString(),
    c.lastMessageTime.toString(),
    c.settingsUpdatedAt.toString(),
    bit210b(c.isGroup),
    encStr210b(c.chatType),
    opt210b(c.groupName),
).joinToString(":")

private fun mutChatFromChat210b(c: Chat): MutChat210b = MutChat210b(
    id = c.id,
    pinnedAt = c.pinnedAt,
    notificationsMuted = c.notificationsMuted,
    archived = c.archived,
    markedUnread = c.markedUnread,
    unreadCount = c.unreadCount,
    lastMessageTime = c.lastMessageTime,
    settingsUpdatedAt = c.settingsUpdatedAt,
    isGroup = c.isGroup,
    chatType = c.chatType,
    groupName = c.groupName,
)

private data class MutCase210b(
    val owner: String = "me",
    val hasSession: Boolean = true,
    val sessFlipAfter: Int = 999,
    val secretEnabled: Boolean = true,
    val seedChats: List<MutChat210b> = emptyList(),
    val inFlightSeed: List<String> = emptyList(),
    val deletingSeed: List<String> = emptyList(),
    val deletedSeed: List<String> = emptyList(),
    val createdSeed: String? = null,
    val ops: List<MutOp210b> = emptyList(),
)

private fun mutReqEnc210b(r: UpdateChatSettingsRequest): String = listOf(
    r.pinned?.toString() ?: "~",
    r.notificationsMuted?.toString() ?: "~",
    r.archived?.toString() ?: "~",
    r.markedUnread?.toString() ?: "~",
).joinToString(":")

private fun mutSettingsEnc210b(s: ChatSettingsResponse): String = listOf(
    encStr210b(s.chatId),
    s.pinnedAt.toString(),
    bit210b(s.notificationsMuted),
    bit210b(s.archived),
    bit210b(s.markedUnread),
    s.updatedAt.toString(),
).joinToString(":")

private fun mutDtoEnc210b(d: ChatDto): String = listOf(
    encStr210b(d.id),
    bit210b(d.isGroup),
    encStr210b(d.chatType),
    encStr210b(d.lastMessageType),
    d.participants.joinToString(";") { encStr210b(it.id) + ":" + encStr210b(it.name) },
).joinToString(":")

private fun mutMapDto210b(d: ChatDto): Chat = Chat(
    id = d.id,
    participants = d.participants.map { p ->
        User(p.id, p.name, p.avatar, p.email, p.isOnline, p.status, lastSeen = p.lastSeen)
    },
    lastMessage = d.lastMessage,
    lastMessageType = MessageType.fromWire(d.lastMessageType),
    lastMessageTime = d.lastMessageTime,
    unreadCount = d.unreadCount,
    isGroup = d.isGroup,
    chatType = d.chatType,
    groupName = d.groupName,
    groupAnnouncement = d.groupAnnouncement,
    groupAvatar = d.groupAvatar,
    memberRevision = d.memberRevision,
    disappearingMessageSeconds = d.disappearingMessageSeconds,
)

// 远程 Result 的脚本化结果：成功值或失败异常描述。
private sealed interface MutRemote210b {
    data class OkSettings(val settings: ChatSettingsResponse) : MutRemote210b
    data object OkUnit : MutRemote210b
    data class OkDto(val dto: ChatDto) : MutRemote210b
    data class Fail(val name: String, val message: String?, val code: String? = null) : MutRemote210b
}

private fun mutFail210b(mode: Int, transferCode: Boolean): MutRemote210b.Fail = when (mode) {
    1 -> MutRemote210b.Fail("RuntimeException", "boom")
    2 -> MutRemote210b.Fail("RuntimeException", null)
    3 -> MutRemote210b.Fail("CancellationException", "cancel-boom")
    4 -> if (transferCode) MutRemote210b.Fail("ApiException", "conf", "GROUP_OWNER_TRANSFER_REQUIRED") else MutRemote210b.Fail("ApiException", "srv")
    5 -> MutRemote210b.Fail("ApiException", "denied")
    else -> MutRemote210b.Fail("RuntimeException", "boom")
}

private fun mutOracle210b(c: MutCase210b): String {
    val effects = mutableListOf<String>()
    var chats = c.seedChats.map { it.toChat() }
    val inFlight = c.inFlightSeed.toMutableSet()
    val deleting = c.deletingSeed.toMutableSet()
    val deleted = c.deletedSeed.toMutableSet()
    var created: String? = c.createdSeed
    val errors = mutableListOf<String>()
    val transfers = mutableListOf<String>()
    val muteApplied = mutableListOf<String>()
    var sessCalls = 0
    var cacheCalls = 0
    fun snap(prefix: String) {
        val snap = listOf(
            chats.joinToString(";") { mutChatEnc210b(mutChatFromChat210b(it)) },
            inFlight.sorted().joinToString(";") { encStr210b(it) },
            deleting.sorted().joinToString(";") { encStr210b(it) },
            deleted.sorted().joinToString(";") { encStr210b(it) },
            opt210b(created),
            errors.joinToString(";") { encStr210b(it) },
            transfers.joinToString(";") { encStr210b(it) },
            muteApplied.joinToString(";") { encStr210b(it) },
        ).joinToString("|")
        effects += prefix + ":" + snap
    }
    fun updateChats(transform: (List<Chat>) -> List<Chat>) {
        chats = transform(chats)
        snap("ST")
    }
    fun showError(msg: String?) {
        effects += "CALL:showError:" + opt210b(msg)
        errors += msg ?: ""
    }
    fun hasSession(): Boolean {
        effects += "CALL:hasSession:" + bit210b(c.hasSession)
        return c.hasSession
    }
    fun sessionMayContinue(): Boolean {
        effects += "CALL:session"
        val idx = sessCalls++
        return idx < c.sessFlipAfter
    }
    // cacheFault 指向本 op 内第几次 cache 调用；命中则按模式抛错。
    fun cacheChats(list: List<Chat>, op: MutOp210b) {
        cacheCalls++
        effects += "CALL:cache:" + cacheCalls + ":" + list.joinToString(";") { mutChatEnc210b(mutChatFromChat210b(it)) }
        when (op.cacheFault) {
            1 -> if (cacheCalls == 1) throw RuntimeException("boom")
            2 -> if (cacheCalls == 2) throw RuntimeException("boom")
            3 -> if (cacheCalls == 1) throw CancellationException("cancel-boom")
            4 -> if (cacheCalls == 2) throw CancellationException("cancel-boom")
        }
    }
    fun logWarning(msg: String, err: Throwable?) {
        effects += "WARN:" + encStr210b(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
    }
    fun remoteSettings(op: MutOp210b, chatId: String, req: UpdateChatSettingsRequest): MutRemote210b {
        val out: MutRemote210b = if (op.remoteMode == 0) {
            MutRemote210b.OkSettings(
                ChatSettingsResponse(chatId, op.setPin, op.setMuted, op.setArch, op.setMarked, op.setUpd),
            )
        } else {
            mutFail210b(op.remoteMode, false)
        }
        val outEnc = when (out) {
            is MutRemote210b.OkSettings -> "OK:" + mutSettingsEnc210b(out.settings)
            is MutRemote210b.Fail -> "ERR:" + out.name + ":" + opt210b(out.message)
            else -> "ERR:RuntimeException:~"
        }
        effects += "CALL:settingsRemote:" + encStr210b(chatId) + ":" + mutReqEnc210b(req) + ":" + outEnc
        return out
    }
    fun remoteDelete(op: MutOp210b, chatId: String): MutRemote210b {
        val out: MutRemote210b = if (op.remoteMode == 0) MutRemote210b.OkUnit else mutFail210b(op.remoteMode, true)
        val outEnc = when (out) {
            is MutRemote210b.OkUnit -> "OK:~"
            is MutRemote210b.Fail -> "ERR:" + out.name + ":" + opt210b(out.message)
            else -> "ERR:RuntimeException:~"
        }
        effects += "CALL:deleteRemote:" + encStr210b(chatId) + ":" + outEnc
        return out
    }
    fun remoteCreate(op: MutOp210b, peerId: String): MutRemote210b {
        val out: MutRemote210b = if (op.remoteMode == 0) {
            MutRemote210b.OkDto(
                ChatDto(
                    id = op.dtoId,
                    participants = op.dtoParts.map { UserDto(it.first, it.second) },
                    isGroup = op.dtoIsGroup,
                    chatType = op.dtoChatType,
                    lastMessageType = op.dtoLastMsgType,
                ),
            )
        } else {
            mutFail210b(op.remoteMode, false)
        }
        val outEnc = when (out) {
            is MutRemote210b.OkDto -> "OK:" + mutDtoEnc210b(out.dto)
            is MutRemote210b.Fail -> "ERR:" + out.name + ":" + opt210b(out.message)
            else -> "ERR:RuntimeException:~"
        }
        effects += "CALL:createRemote:" + encStr210b(peerId) + ":" + outEnc
        return out
    }
    fun cleanupLocal(op: MutOp210b, chatId: String) {
        effects += "CALL:cleanup:" + encStr210b(chatId) + ":" + op.sessionGen
        when (op.cleanupMode) {
            1 -> throw RuntimeException("boom")
            2 -> throw RuntimeException()
            3 -> throw CancellationException("cancel-boom")
        }
    }
    fun updateChatSettings(op: MutOp210b) {
        if (op.chatIdx !in chats.indices) return
        val chat = chats[op.chatIdx]
        val optimistic = chat.copy(
            pinnedAt = op.optPin,
            notificationsMuted = op.optMuted,
            archived = op.optArch,
            markedUnread = op.optMarked,
        )
        val request = UpdateChatSettingsRequest(
            pinned = tri210b(op.reqPin),
            notificationsMuted = tri210b(op.reqMuted),
            archived = tri210b(op.reqArch),
            markedUnread = tri210b(op.reqMarked),
        )
        if (!inFlight.add(chat.id)) return
        val owner = c.owner
        if (!hasSession() || owner.isBlank()) {
            showError("T:SESSION_EXPIRED")
            inFlight.remove(chat.id)
            return
        }
        updateChats { list -> list.map { if (it.id == chat.id) optimistic else it } }
        if (optimistic.notificationsMuted && !chat.notificationsMuted) {
            effects += "CALL:muteApplied:" + encStr210b(chat.id)
            muteApplied += chat.id
        }
        try {
            if (!sessionMayContinue()) return
            try {
                cacheChats(listOf(optimistic), op)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("optimistic cacheChats failed for " + chat.id, e)
            }
            if (!sessionMayContinue()) return
            when (val result = remoteSettings(op, chat.id, request)) {
                is MutRemote210b.OkSettings -> {
                    if (!sessionMayContinue()) return
                    val confirmed = applyConfirmedSettings(optimistic, result.settings)
                    cacheChats(listOf(confirmed), op)
                    if (!sessionMayContinue()) return
                    updateChats { list -> list.map { if (it.id == chat.id) confirmed else it } }
                }
                is MutRemote210b.Fail -> {
                    if (result.name == "CancellationException") throw CancellationException("cancel-boom")
                    if (!sessionMayContinue()) return
                    try {
                        cacheChats(listOf(chat), op)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    if (!sessionMayContinue()) return
                    updateChats { list -> list.map { if (it.id == chat.id) chat else it } }
                    showError("T:SETTINGS_SYNC_FAILED")
                }
                else -> Unit
            }
        } catch (e: CancellationException) {
            // launch 内的 CE 被协程机制静默吞掉（handler 不收 CE），不记 token。
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        } finally {
            inFlight.remove(chat.id)
        }
    }
    fun deleteChat(op: MutOp210b) {
        val owner = c.owner
        if (!hasSession() || owner.isBlank()) {
            showError("T:SESSION_EXPIRED")
            return
        }
        if (deleting.contains(op.chatId)) return
        deleting.add(op.chatId)
        try {
            if (!sessionMayContinue()) return
            val previous = chats.find { it.id == op.chatId }
            updateChats { list -> list.filterNot { it.id == op.chatId } }
            var leaveConfirmed = false
            try {
                if (!sessionMayContinue()) return
                val result = remoteDelete(op, op.chatId)
                if (result is MutRemote210b.Fail && result.name == "CancellationException") {
                    throw CancellationException("cancel-boom")
                }
                if (!sessionMayContinue()) return
                if (result is MutRemote210b.Fail) {
                    logWarning("deleteChat failed: " + (result.message ?: "unknown"), null)
                    deleted.remove(op.chatId)
                    if (previous != null) {
                        updateChats { list ->
                            if (list.none { it.id == op.chatId }) restoreChatSorted(list, previous) else list
                        }
                    }
                    if (result.code == "GROUP_OWNER_TRANSFER_REQUIRED") {
                        effects += "CALL:transfer:" + encStr210b(op.chatId)
                        transfers += op.chatId
                    } else {
                        showError("T:CHAT_LEAVE_FAILED")
                    }
                    return
                }
                leaveConfirmed = true
                deleted.add(op.chatId)
                cleanupLocal(op, op.chatId)
            } catch (e: CancellationException) {
                deleted.remove(op.chatId)
                if (!leaveConfirmed && previous != null && sessionMayContinue() &&
                    chats.none { it.id == op.chatId }
                ) {
                    updateChats { list -> restoreChatSorted(list, previous) }
                }
                throw e
            }
        } catch (e: CancellationException) {
            // 同上：launch 内的 CE 不可观测，不记 token。
        } catch (e: Exception) {
            logWarning("deleteChat cleanup failed", e)
        } finally {
            deleting.remove(op.chatId)
        }
    }
    fun startSecretChat(op: MutOp210b) {
        if (op.peerId.isBlank()) return
        if (!c.secretEnabled) {
            showError("T:SECRET_CHAT_DISABLED")
            return
        }
        val owner = c.owner
        if (!hasSession() || owner.isBlank()) {
            showError("T:SESSION_EXPIRED")
            return
        }
        if (!sessionMayContinue()) {
            showError("T:SESSION_EXPIRED")
            return
        }
        when (val result = remoteCreate(op, op.peerId)) {
            is MutRemote210b.OkDto -> {
                effects += "CALL:touch:" + encStr210b(result.dto.id)
                val mapped = mutMapDto210b(result.dto)
                runCatching { cacheChats(listOf(mapped), op) }
                effects += "CALL:created:" + encStr210b(result.dto.id)
                created = result.dto.id
            }
            is MutRemote210b.Fail -> {
                showError(result.message ?: "T:SECRET_CHAT_START_FAILED")
            }
            else -> Unit
        }
    }
    for (op in c.ops) {
        cacheCalls = 0
        try {
            when (op.kind) {
                0 -> updateChatSettings(op)
                1 -> deleteChat(op)
                2 -> startSecretChat(op)
                else -> {
                    effects += "CALL:created:~"
                    created = null
                }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun mutThrowRemote210b(mode: Int, transferCode: Boolean): Result<Nothing> {
    val fail = mutFail210b(mode, transferCode)
    return when (fail.name) {
        "CancellationException" -> Result.failure(CancellationException("cancel-boom"))
        "ApiException" -> if (transferCode && mode == 4) {
            Result.failure(ApiException(ApiFailureKind.HTTP, 409, "conf", "GROUP_OWNER_TRANSFER_REQUIRED"))
        } else if (mode == 5) {
            Result.failure(ApiException(ApiFailureKind.HTTP, 403, "denied", null))
        } else {
            Result.failure(ApiException(ApiFailureKind.HTTP, 500, "srv", null))
        }
        else -> Result.failure(RuntimeException(fail.message))
    }
}

private fun mutProd210b(c: MutCase210b): String {
    val effects = mutableListOf<String>()
    var chats = c.seedChats.map { it.toChat() }
    val inFlight = c.inFlightSeed.toMutableSet()
    val deleting = c.deletingSeed.toMutableSet()
    val deleted = c.deletedSeed.toMutableSet()
    var created: String? = c.createdSeed
    val errors = mutableListOf<String>()
    val transfers = mutableListOf<String>()
    val muteApplied = mutableListOf<String>()
    var sessCalls = 0
    var cacheCalls = 0
    fun snap(prefix: String) {
        val snap = listOf(
            chats.joinToString(";") { mutChatEnc210b(mutChatFromChat210b(it)) },
            inFlight.sorted().joinToString(";") { encStr210b(it) },
            deleting.sorted().joinToString(";") { encStr210b(it) },
            deleted.sorted().joinToString(";") { encStr210b(it) },
            opt210b(created),
            errors.joinToString(";") { encStr210b(it) },
            transfers.joinToString(";") { encStr210b(it) },
            muteApplied.joinToString(";") { encStr210b(it) },
        ).joinToString("|")
        effects += prefix + ":" + snap
    }
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            repeat(100) {
                val kids = scope.coroutineContext[Job]?.children?.toList() ?: emptyList()
                if (kids.isEmpty()) return@runBlocking
                for (child in kids) runCatching { child.join() }
            }
        }
    }
    var curOp = MutOp210b(0)
    val coordinator = ChatListMutationCoordinator(
        scope = scope,
        getChats = { chats },
        updateChats = { transform ->
            chats = transform(chats)
            snap("ST")
        },
        showError = { msg ->
            effects += "CALL:showError:" + opt210b(msg)
            errors += msg ?: ""
        },
        isDeletingChat = { deleting.contains(it) },
        addDeletingChat = { deleting.add(it) },
        removeDeletingChat = { deleting.remove(it) },
        setCreatedSecretChatId = { id ->
            effects += "CALL:created:" + opt210b(id)
            created = id
        },
        showOwnerTransferRequired = { id ->
            effects += "CALL:transfer:" + encStr210b(id)
            transfers += id
        },
        deletedChatIds = deleted,
        settingsInFlight = inFlight,
        ownerUserId = { c.owner },
        hasSession = {
            effects += "CALL:hasSession:" + bit210b(c.hasSession)
            c.hasSession
        },
        sessionMayContinue = {
            effects += "CALL:session"
            val idx = sessCalls++
            idx < c.sessFlipAfter
        },
        cacheChats = { list ->
            cacheCalls++
            effects += "CALL:cache:" + cacheCalls + ":" + list.joinToString(";") { mutChatEnc210b(mutChatFromChat210b(it)) }
            when (curOp.cacheFault) {
                1 -> if (cacheCalls == 1) throw RuntimeException("boom")
                2 -> if (cacheCalls == 2) throw RuntimeException("boom")
                3 -> if (cacheCalls == 1) throw CancellationException("cancel-boom")
                4 -> if (cacheCalls == 2) throw CancellationException("cancel-boom")
            }
        },
        updateChatSettingsRemote = { chatId, req ->
            val out: Result<ChatSettingsResponse> = if (curOp.remoteMode == 0) {
                Result.success(
                    ChatSettingsResponse(chatId, curOp.setPin, curOp.setMuted, curOp.setArch, curOp.setMarked, curOp.setUpd),
                )
            } else {
                mutThrowRemote210b(curOp.remoteMode, false)
            }
            val outEnc = if (out.isSuccess) {
                "OK:" + mutSettingsEnc210b(out.getOrThrow())
            } else {
                val e = out.exceptionOrNull()!!
                "ERR:" + e.javaClass.simpleName + ":" + opt210b(e.message)
            }
            effects += "CALL:settingsRemote:" + encStr210b(chatId) + ":" + mutReqEnc210b(req) + ":" + outEnc
            out
        },
        deleteChatRemote = { chatId ->
            val out: Result<Unit> = if (curOp.remoteMode == 0) {
                Result.success(Unit)
            } else {
                mutThrowRemote210b(curOp.remoteMode, true)
            }
            val outEnc = if (out.isSuccess) {
                "OK:~"
            } else {
                val e = out.exceptionOrNull()!!
                "ERR:" + e.javaClass.simpleName + ":" + opt210b(e.message)
            }
            effects += "CALL:deleteRemote:" + encStr210b(chatId) + ":" + outEnc
            out
        },
        createChatRemote = { peerIds, isGroup, groupName, chatType ->
            val out: Result<ChatDto> = if (curOp.remoteMode == 0) {
                Result.success(
                    ChatDto(
                        id = curOp.dtoId,
                        participants = curOp.dtoParts.map { UserDto(it.first, it.second) },
                        isGroup = curOp.dtoIsGroup,
                        chatType = curOp.dtoChatType,
                        lastMessageType = curOp.dtoLastMsgType,
                    ),
                )
            } else {
                mutThrowRemote210b(curOp.remoteMode, false)
            }
            val outEnc = if (out.isSuccess) {
                "OK:" + mutDtoEnc210b(out.getOrThrow())
            } else {
                val e = out.exceptionOrNull()!!
                "ERR:" + e.javaClass.simpleName + ":" + opt210b(e.message)
            }
            effects += "CALL:createRemote:" + encStr210b(peerIds.joinToString(",")) + ":" + outEnc
            out
        },
        touchSecretChat = { chatId ->
            effects += "CALL:touch:" + encStr210b(chatId)
        },
        cleanupLocalChat = { chatId, session ->
            effects += "CALL:cleanup:" + encStr210b(chatId) + ":" + session.generation
            when (curOp.cleanupMode) {
                1 -> throw RuntimeException("boom")
                2 -> throw RuntimeException()
                3 -> throw CancellationException("cancel-boom")
            }
        },
        cleanupSessionFor = { owner -> ConversationLocalCleanupSession(owner, curOp.sessionGen) },
        onMuteApplied = { chatId ->
            effects += "CALL:muteApplied:" + encStr210b(chatId)
            muteApplied += chatId
        },
        sessionExpiredText = { "T:SESSION_EXPIRED" },
        settingsSyncFailedText = { "T:SETTINGS_SYNC_FAILED" },
        chatLeaveFailedText = { "T:CHAT_LEAVE_FAILED" },
        secretChatFeatureEnabled = { c.secretEnabled },
        secretChatDisabledMessage = { "T:SECRET_CHAT_DISABLED" },
        secretChatStartFailedMessage = { "T:SECRET_CHAT_START_FAILED" },
        logWarning = { msg, err ->
            effects += "WARN:" + encStr210b(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
        },
        ioDispatcher = Dispatchers.Unconfined,
    )
    for (op in c.ops) {
        curOp = op
        cacheCalls = 0
        try {
            when (op.kind) {
                0 -> {
                    if (op.chatIdx in chats.indices) {
                        val chat = chats[op.chatIdx]
                        val optimistic = chat.copy(
                            pinnedAt = op.optPin,
                            notificationsMuted = op.optMuted,
                            archived = op.optArch,
                            markedUnread = op.optMarked,
                        )
                        val request = UpdateChatSettingsRequest(
                            pinned = tri210b(op.reqPin),
                            notificationsMuted = tri210b(op.reqMuted),
                            archived = tri210b(op.reqArch),
                            markedUnread = tri210b(op.reqMarked),
                        )
                        coordinator.updateChatSettings(chat, optimistic, request)
                    }
                }
                1 -> coordinator.deleteChat(op.chatId)
                2 -> coordinator.startSecretChatWithPeer(op.peerId)
                else -> coordinator.clearCreatedSecretChat()
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val MUT_CHAT_IDS210b = listOf("c1", "c2", "c3")

private fun randomMutChat210b(r: Random, id: String): MutChat210b = MutChat210b(
    id = id,
    pinnedAt = listOf(0L, 100L, 200L)[r.nextInt(3)],
    notificationsMuted = r.nextInt(2) == 0,
    archived = r.nextInt(2) == 0,
    markedUnread = r.nextInt(2) == 0,
    unreadCount = r.nextInt(0, 5),
    lastMessageTime = listOf(0L, 1000L, 2000L)[r.nextInt(3)],
    settingsUpdatedAt = listOf(0L, 500L)[r.nextInt(2)],
    isGroup = r.nextInt(2) == 0,
    chatType = listOf("DIRECT", "GROUP", "SECRET")[r.nextInt(3)],
    groupName = listOf<String?>(null, "G", "")[r.nextInt(3)],
)

private fun randomMutOp210b(r: Random): MutOp210b {
    val kind = r.nextInt(4)
    return MutOp210b(
        kind = kind,
        chatIdx = r.nextInt(3),
        optPin = listOf(0L, 100L, 300L)[r.nextInt(3)],
        optMuted = r.nextInt(2) == 0,
        optArch = r.nextInt(2) == 0,
        optMarked = r.nextInt(2) == 0,
        reqPin = r.nextInt(-1, 2),
        reqMuted = r.nextInt(-1, 2),
        reqArch = r.nextInt(-1, 2),
        reqMarked = r.nextInt(-1, 2),
        remoteMode = r.nextInt(6),
        setPin = listOf(0L, 400L)[r.nextInt(2)],
        setMuted = r.nextInt(2) == 0,
        setArch = r.nextInt(2) == 0,
        setMarked = r.nextInt(2) == 0,
        setUpd = listOf(0L, 900L)[r.nextInt(2)],
        cacheFault = r.nextInt(5),
        chatId = listOf("c1", "c2", "ghost", "")[r.nextInt(4)],
        peerId = listOf("u1", "u2", "", " ")[r.nextInt(4)],
        dtoId = listOf("s1", "s2")[r.nextInt(2)],
        dtoIsGroup = r.nextInt(2) == 0,
        dtoChatType = listOf("SECRET", "DIRECT", "GROUP")[r.nextInt(3)],
        dtoLastMsgType = listOf("TEXT", "IMAGE", "BOGUS")[r.nextInt(3)],
        dtoParts = List(r.nextInt(0, 3)) {
            ("u" + r.nextInt(1, 4)) to listOf("Ann", "Bob", "")[r.nextInt(3)]
        },
        cleanupMode = r.nextInt(4),
        sessionGen = listOf(1L, 2L)[r.nextInt(2)],
    )
}

private fun randomMutCase210b(r: Random): MutCase210b {
    val n = r.nextInt(0, 4)
    return MutCase210b(
        owner = listOf("me", "u1", "")[r.nextInt(3)],
        hasSession = r.nextInt(5) != 0,
        sessFlipAfter = listOf(0, 1, 2, 3, 4, 999)[r.nextInt(6)],
        secretEnabled = r.nextInt(5) != 0,
        seedChats = (0 until n).map { randomMutChat210b(r, MUT_CHAT_IDS210b[it]) },
        inFlightSeed = if (r.nextInt(6) == 0) listOf("c1") else emptyList(),
        deletingSeed = if (r.nextInt(6) == 0) listOf("c2") else emptyList(),
        deletedSeed = if (r.nextInt(6) == 0) listOf("c3") else emptyList(),
        createdSeed = if (r.nextInt(6) == 0) "s0" else null,
        ops = List(r.nextInt(0, 9)) { randomMutOp210b(r) },
    )
}

private fun boundaryMutCase210b(r: Random): MutCase210b {
    val seeds = listOf(
        MutChat210b("c1", pinnedAt = 100, lastMessageTime = 1000),
        MutChat210b("c2", pinnedAt = 0, lastMessageTime = 2000),
        MutChat210b("c3", pinnedAt = 100, lastMessageTime = 500),
    )
    val base = MutCase210b(seedChats = seeds)
    return when (r.nextInt(26)) {
        0 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1)))
        1 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, remoteMode = 1)))
        2 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, cacheFault = 1)))
        3 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, cacheFault = 2)))
        4 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, cacheFault = 3)))
        5 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, cacheFault = 4)))
        6 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0, remoteMode = 3)))
        7 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0)), inFlightSeed = listOf("c1"))
        8 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0)), hasSession = false)
        9 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0)), sessFlipAfter = 1)
        10 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 0)), sessFlipAfter = 2)
        11 -> base.copy(ops = listOf(MutOp210b(0, chatIdx = 5)))
        12 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c1")))
        13 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c1", remoteMode = 1)))
        14 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c1", remoteMode = 4)))
        15 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c1", remoteMode = 3)))
        16 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c1", cleanupMode = 1)))
        17 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c1", cleanupMode = 3)))
        18 -> base.copy(ops = listOf(MutOp210b(1, chatId = "ghost")))
        19 -> base.copy(ops = listOf(MutOp210b(1, chatId = "c2")), deletingSeed = listOf("c2"))
        20 -> base.copy(ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1")))
        21 -> base.copy(ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1", remoteMode = 1)))
        22 -> base.copy(ops = listOf(MutOp210b(2, peerId = "", dtoId = "s1")))
        23 -> base.copy(ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1")), secretEnabled = false)
        24 -> base.copy(ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1", cacheFault = 1)))
        else -> base.copy(ops = listOf(MutOp210b(3)), createdSeed = "s0")
    }
}

// ---------- tests ----------

class ChatListMutationCoordinatorFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(210205)
        repeat(300) {
            val c = randomMutCase210b(r)
            assertEquals(mutOracle210b(c), mutProd210b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(210206)
        repeat(200) {
            val c = boundaryMutCase210b(r)
            assertEquals(mutOracle210b(c), mutProd210b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val seeds = listOf(
            MutChat210b("c1", pinnedAt = 100, lastMessageTime = 1000),
            MutChat210b("c2", pinnedAt = 0, lastMessageTime = 2000),
        )
        val cases = listOf(
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, remoteMode = 1))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(0, chatIdx = 0, optMuted = true, reqMuted = 1, cacheFault = 2))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(1, chatId = "c1"))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(1, chatId = "c1", remoteMode = 4))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(1, chatId = "c1", remoteMode = 3))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(1, chatId = "c1", cleanupMode = 3))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1"))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1", remoteMode = 1))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(2, peerId = "u1", dtoId = "s1", cacheFault = 1))),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(3)), createdSeed = "s0"),
            MutCase210b(seedChats = seeds, ops = listOf(MutOp210b(0, chatIdx = 0)), sessFlipAfter = 1),
            MutCase210b(seedChats = seeds, ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(mutOracle210b(c), mutProd210b(c))
        }
    }
}
