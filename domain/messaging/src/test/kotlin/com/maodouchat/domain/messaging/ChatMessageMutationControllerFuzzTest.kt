package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ConversationMessageMutationCoordinator
import com.maodouchat.core.model.ConversationMessageMutationOutcome
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageMutationKind
import com.maodouchat.core.model.MessageMutationProjection
import com.maodouchat.core.model.MessageMutationTicket
import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2MutationCommit
import com.maodouchat.core.model.MessagingV2MutationFacade
import com.maodouchat.core.model.PinnedMessageDto
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
private fun encStr211b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit211b(b: Boolean): String = if (b) "1" else "0"

private fun opt211b(s: String?): String = s?.let { encStr211b(it) } ?: "~"

private fun optLong211b(v: Long?): String = v?.toString() ?: "~"

// 编辑窗口 5 分钟；种子时间戳取 nowBase 前 60 秒（窗内）或 600 秒（窗外），
// 距边界各 240 秒以上，驱动耗时不会翻边。
private const val EDIT_WINDOW_MS211b = 300_000L

private data class MsgMeta211b(
    val mentions: List<String> = emptyList(),
    val replyToId: String? = null,
    val aiAssisted: Boolean = false,
    val aiAssistantMode: String? = null,
    val markdown: Boolean = false,
)

private fun MsgMeta211b.toMeta(): MessageMeta = MessageMeta(
    mentions = mentions,
    replyToId = replyToId,
    aiAssisted = aiAssisted,
    aiAssistantMode = aiAssistantMode,
    markdown = markdown,
)

private fun msgMetaFrom211b(m: MessageMeta): MsgMeta211b = MsgMeta211b(
    mentions = m.mentions,
    replyToId = m.replyToId,
    aiAssisted = m.aiAssisted,
    aiAssistantMode = m.aiAssistantMode,
    markdown = m.markdown,
)

private fun msgMetaEnc211b(m: MsgMeta211b): String = listOf(
    m.mentions.joinToString(",") { encStr211b(it) },
    opt211b(m.replyToId),
    bit211b(m.aiAssisted),
    opt211b(m.aiAssistantMode),
    bit211b(m.markdown),
).joinToString("|")

private data class MsgReaction211b(
    val userId: String,
    val emoji: String,
    val reactedAt: Long,
)

private fun MsgReaction211b.toReaction(): MessageReaction =
    MessageReaction(userId, emoji, reactedAt)

private fun msgReactionFrom211b(r: MessageReaction): MsgReaction211b =
    MsgReaction211b(r.userId, r.emoji, r.reactedAt)

private fun msgReactionEnc211b(r: MsgReaction211b): String =
    encStr211b(r.userId) + "," + encStr211b(r.emoji) + "," + r.reactedAt

private fun msgReactionsEnc211b(rs: List<MsgReaction211b>): String =
    rs.joinToString("+") { msgReactionEnc211b(it) }

// 种子消息 editedAt 恒非空：revoke/edit 经 facade 的 editedAt 回填取
// System.currentTimeMillis()，空值会把墙钟漏进 enqueue token。
private data class MsgMsg211b(
    val id: String,
    val chatId: String = "c1",
    val senderId: String = "me",
    val content: String = "hello",
    val type: String = "TEXT",
    val timestamp: Long = 1000L,
    val status: String = "SENT",
    val editedAt: Long = 1005L,
    val starred: Boolean = false,
    val meta: MsgMeta211b = MsgMeta211b(),
    val reactions: List<MsgReaction211b> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false,
)

private fun MsgMsg211b.toMessage(): Message = Message(
    id = id,
    chatId = chatId,
    senderId = senderId,
    content = content,
    type = MessageType.valueOf(type),
    timestamp = timestamp,
    status = MessageStatus.valueOf(status),
    editedAt = editedAt,
    starred = starred,
    meta = meta.toMeta(),
    reactions = reactions.map { it.toReaction() },
    expiresAt = expiresAt,
    sealedSender = sealedSender,
)

private fun msgMsgFrom211b(m: Message): MsgMsg211b = MsgMsg211b(
    id = m.id,
    chatId = m.chatId,
    senderId = m.senderId,
    content = m.content,
    type = m.type.name,
    timestamp = m.timestamp,
    status = m.status.name,
    editedAt = m.editedAt ?: m.timestamp,
    starred = m.starred,
    meta = msgMetaFrom211b(m.meta),
    reactions = m.reactions.map { msgReactionFrom211b(it) },
    expiresAt = m.expiresAt,
    sealedSender = m.sealedSender,
)

private fun msgMsgEnc211b(m: MsgMsg211b): String = listOf(
    encStr211b(m.id),
    encStr211b(m.chatId),
    encStr211b(m.senderId),
    encStr211b(m.content),
    m.type,
    m.timestamp.toString(),
    m.status,
    m.editedAt.toString(),
    bit211b(m.starred),
    msgMetaEnc211b(m.meta),
    msgReactionsEnc211b(m.reactions),
    optLong211b(m.expiresAt),
    bit211b(m.sealedSender),
).joinToString(":")

private data class MsgPin211b(
    val chatId: String,
    val messageId: String,
    val pinnedBy: String = "me",
    val pinnedAt: Long = 0L,
)

private fun MsgPin211b.toDto(): PinnedMessageDto =
    PinnedMessageDto(chatId, messageId, pinnedBy, pinnedAt)

private fun msgPinFrom211b(p: PinnedMessageDto): MsgPin211b =
    MsgPin211b(p.chatId, p.messageId, p.pinnedBy, p.pinnedAt)

private fun msgPinEnc211b(p: MsgPin211b): String = listOf(
    encStr211b(p.chatId),
    encStr211b(p.messageId),
    encStr211b(p.pinnedBy),
    p.pinnedAt.toString(),
).joinToString(":")

// op kind：0 deleteMessage，1 deleteMessagesBatch，2 revokeMessage，
// 3 editTextMessage，4 setMessageReaction。
// enqueueFault：0 成功、1 抛 RT 带消息、2 抛 RT 空消息、3 抛 CE。
// persistFault：0 成功、1 首个持久步抛 RT 带消息、2 抛 RT 空消息、3 抛 CE
// （facade 的 completeCommittedProjection 会把 CE 也收成 projectionError）。
// cleanupFault / notifFault / refreshFault / indexFault：0 成功、1 抛 RT。
// persistNull：仅 edit，persistEdited 返回 null（不调 indexMessage）。
private data class MsgOp211b(
    val kind: Int,
    val messageId: String = "",
    val messageIds: List<String> = emptyList(),
    val newText: String = "",
    val emoji: String = "",
    val enqueueFault: Int = 0,
    val persistFault: Int = 0,
    val cleanupFault: Int = 0,
    val notifFault: Int = 0,
    val refreshFault: Int = 0,
    val indexFault: Int = 0,
    val persistNull: Boolean = false,
)

private data class MsgCase211b(
    val nowBase: Long = System.currentTimeMillis(),
    val owner: String = "me",
    val token: String = "tok",
    val groupRevision: Long? = 7L,
    val revokeEnabled: Boolean = true,
    val editEnabled: Boolean = true,
    val requireReactions: Boolean = true,
    val sessOk: Boolean = true,
    val seedMessages: List<MsgMsg211b> = emptyList(),
    val seedPins: List<MsgPin211b> = emptyList(),
    val seedWarning: String? = null,
    val ops: List<MsgOp211b> = emptyList(),
)

private fun msgText211b(t: ChatMessageMutationText): String = "T:" + t.name

private fun msgWithinWindow211b(now: Long, sentAt: Long): Boolean =
    now - sentAt < EDIT_WINDOW_MS211b

// ReactionMutationPolicy.apply 的纯镜像。
private fun msgApplyReactions211b(
    existing: List<MsgReaction211b>,
    actorUserId: String,
    emoji: String?,
    reactedAt: Long,
): List<MsgReaction211b> {
    if (actorUserId.isBlank()) return existing
    val withoutActor = existing.filterNot { it.userId == actorUserId }
    val normalized = emoji?.trim()?.takeIf { it.isNotEmpty() } ?: return withoutActor
    return withoutActor + MsgReaction211b(actorUserId, normalized, reactedAt)
}

private sealed interface MsgOutcome211b {
    data class Applied(val removePinned: Boolean, val localError: String?) : MsgOutcome211b
    data class Failed(val name: String, val message: String?) : MsgOutcome211b
    data object Ignored : MsgOutcome211b
}

private fun msgOracle211b(c: MsgCase211b): String {
    val effects = mutableListOf<String>()
    var messages = c.seedMessages.map { it.toMessage() }
    var pins = c.seedPins.map { it.toDto() }
    var warning: String? = c.seedWarning
    val pending = mutableMapOf<String, MessageMutationTicket>()
    val deleted = mutableSetOf<String>()
    val revoked = mutableSetOf<String>()
    var generation = 0L
    fun snap(prefix: String) {
        val snap = listOf(
            messages.joinToString(";") { msgMsgEnc211b(msgMsgFrom211b(it)) },
            pins.joinToString(";") { msgPinEnc211b(msgPinFrom211b(it)) },
            opt211b(warning),
        ).joinToString("|")
        effects += prefix + ":" + snap
    }
    fun updateState(transform: (ChatDetailUiState) -> ChatDetailUiState) {
        val before = ChatDetailUiState(messages = messages, pinnedMessages = pins, groupEncryptionWarning = warning)
        val after = transform(before)
        messages = after.messages
        pins = after.pinnedMessages
        warning = after.groupEncryptionWarning
        snap("ST")
    }
    fun logWarning(msg: String, err: Throwable?) {
        effects += "WARN:" + encStr211b(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
    }
    fun projectMutation(proj: MessageMutationProjection) {
        updateState { state ->
            when (proj) {
                is MessageMutationProjection.Remove -> state.copy(
                    messages = state.messages.filterNot { it.id == proj.messageId },
                )
                is MessageMutationProjection.Set -> state.copy(
                    messages = state.messages.map { if (it.id == proj.message.id) proj.message else it },
                )
            }
        }
    }
    fun projectReaction(msg: Message) {
        updateState { state ->
            if (state.messages.none { it.id == msg.id }) {
                state
            } else {
                state.copy(
                    messages = state.messages.map { if (it.id == msg.id) msg else it },
                )
            }
        }
    }
    fun beginTicket(id: String, kind: MessageMutationKind): MessageMutationTicket? {
        if (id.isBlank() || id in deleted || pending.containsKey(id)) return null
        return MessageMutationTicket(id, kind, ++generation).also { pending[id] = it }
    }
    fun shouldRollback(ticket: MessageMutationTicket): Boolean {
        if (pending[ticket.messageId] != ticket) return false
        pending.remove(ticket.messageId)
        return ticket.messageId !in deleted && ticket.messageId !in revoked
    }
    fun completeTicket(ticket: MessageMutationTicket): Boolean {
        if (pending[ticket.messageId] != ticket) return false
        pending.remove(ticket.messageId)
        return true
    }
    // facade 的脚本化镜像：requireCurrentOwner → enqueue → 本地投影步 →
    // refreshPreview；投影步失败全部收进 localError（CE 亦然）。
    fun facadeCall(op: MsgOp211b, action: String, original: Message, updated: Message?, actorChoice: String?): MsgOutcome211b {
        if (!c.sessOk) {
            throw IllegalArgumentException("messaging_v2_owner_session_changed")
        }
        val content = when (action) {
            "REVOKE" -> updated?.content
            "EDIT" -> updated?.content
            else -> null
        }
        val editedAt = when (action) {
            "REVOKE", "EDIT" -> updated?.editedAt
            else -> null
        }
        effects += "CALL:enqueue:" + action + ":" + encStr211b(original.chatId) + ":" +
            encStr211b(original.id) + ":" + opt211b(content) + ":" +
            optLong211b(editedAt) + ":" + opt211b(actorChoice) + ":" +
            optLong211b(c.groupRevision)
        when (op.enqueueFault) {
            1 -> throw RuntimeException("boom")
            2 -> throw RuntimeException()
            3 -> throw CancellationException("cancel-boom")
        }
        var firstError: String? = null
        fun noteError(name: String) {
            if (firstError == null) firstError = name
        }
        fun persistStep(): Boolean {
            return when (op.persistFault) {
                1 -> {
                    noteError("RuntimeException")
                    false
                }
                2 -> {
                    noteError("RuntimeException")
                    false
                }
                3 -> {
                    noteError("CancellationException")
                    false
                }
                else -> true
            }
        }
        when (action) {
            "DELETE" -> {
                effects += "CALL:persistDeleted:" + encStr211b(original.id)
                persistStep()
                effects += "CALL:cleanupAttach:" + encStr211b(original.id)
                if (op.cleanupFault == 1) noteError("RuntimeException")
                effects += "CALL:cleanupNotif:" + encStr211b(original.id)
                if (op.notifFault == 1) noteError("RuntimeException")
            }
            "REVOKE" -> {
                effects += "CALL:persistRevoked:" + encStr211b(original.id) + ":" +
                    msgMsgEnc211b(msgMsgFrom211b(updated!!))
                persistStep()
                effects += "CALL:cleanupAttach:" + encStr211b(original.id)
                if (op.cleanupFault == 1) noteError("RuntimeException")
                effects += "CALL:cleanupNotif:" + encStr211b(original.id)
                if (op.notifFault == 1) noteError("RuntimeException")
            }
            "EDIT" -> {
                effects += "CALL:persistEdited:" + msgMsgEnc211b(msgMsgFrom211b(updated!!))
                val ok = persistStep()
                if (ok && !op.persistNull) {
                    effects += "CALL:indexMsg:" + encStr211b(updated.id)
                    if (op.indexFault == 1) noteError("RuntimeException")
                }
            }
            "REACTION_SET" -> {
                effects += "CALL:persistReaction:" + encStr211b(original.id) + ":" +
                    msgReactionsEnc211b(updated!!.reactions.map { msgReactionFrom211b(it) }) + ":" +
                    encStr211b(c.owner) + ":" + opt211b(actorChoice)
                persistStep()
            }
        }
        if (action != "REACTION_SET") {
            effects += "CALL:refreshPreview:" + encStr211b(original.chatId)
            if (op.refreshFault == 1) noteError("RuntimeException")
        }
        return MsgOutcome211b.Applied(false, firstError)
    }
    fun execute(
        op: MsgOp211b,
        kind: MessageMutationKind,
        original: Message,
        optimistic: MessageMutationProjection,
        rollback: MessageMutationProjection,
        removePinned: Boolean,
        action: String,
        updated: Message?,
        actorChoice: String? = null,
    ): MsgOutcome211b {
        val ticket = beginTicket(original.id, kind) ?: return MsgOutcome211b.Ignored
        projectMutation(optimistic)
        return try {
            val commit = facadeCall(op, action, original, updated, actorChoice)
            completeTicket(ticket)
            when (commit) {
                is MsgOutcome211b.Applied -> commit.copy(removePinned = removePinned)
                else -> commit
            }
        } catch (e: CancellationException) {
            if (shouldRollback(ticket)) projectMutation(rollback)
            throw e
        } catch (e: Exception) {
            val rolledBack = shouldRollback(ticket)
            if (rolledBack) projectMutation(rollback)
            if (rolledBack) {
                MsgOutcome211b.Failed(e.javaClass.simpleName, e.message)
            } else {
                MsgOutcome211b.Applied(removePinned, null)
            }
        }
    }
    fun applyOutcome(outcome: MsgOutcome211b, messageId: String, failureText: String) {
        when (outcome) {
            is MsgOutcome211b.Applied -> updateState { state ->
                state.copy(
                    pinnedMessages = if (outcome.removePinned) {
                        state.pinnedMessages.filterNot { it.messageId == messageId }
                    } else {
                        state.pinnedMessages
                    },
                    groupEncryptionWarning = if (outcome.localError != null) {
                        msgText211b(ChatMessageMutationText.MUTATION_COMMITTED_LOCAL_SYNC_PENDING)
                    } else {
                        state.groupEncryptionWarning
                    },
                )
            }
            is MsgOutcome211b.Failed -> updateState {
                it.copy(groupEncryptionWarning = outcome.message ?: failureText)
            }
            MsgOutcome211b.Ignored -> Unit
        }
    }
    // launch 体：CE 被协程机制静默吞掉（不记 token），其他异常由 handler 记一次。
    fun launchBody(body: () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            // 不记 token。
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
    }
    fun deleteOne(op: MsgOp211b, messageId: String) {
        val original = messages.find { it.id == messageId } ?: return
        val outcome = execute(
            op, MessageMutationKind.DELETE, original,
            MessageMutationProjection.Remove(original.id),
            MessageMutationProjection.Set(original),
            true, "DELETE", null,
        )
        applyOutcome(outcome, messageId, msgText211b(ChatMessageMutationText.DELETE_SERVER_FAILED))
    }
    fun rollbackActorChoice(op: MsgOp211b, original: Message) {
        val current = messages.find { it.id == original.id }
            ?.takeUnless { it.type == MessageType.REVOKED } ?: return
        val originalActor = original.reactions.lastOrNull { it.userId == c.owner }
        val restored = current.copy(
            reactions = msgApplyReactions211b(
                current.reactions.map { msgReactionFrom211b(it) },
                c.owner,
                originalActor?.emoji,
                originalActor?.reactedAt ?: c.nowBase,
            ).map { it.toReaction() },
        )
        projectReaction(restored)
    }
    fun toggleReaction(op: MsgOp211b): MsgOutcome211b {
        val normalizedEmoji = op.emoji.trim()
        if (op.messageId.isBlank() || normalizedEmoji.isBlank() || c.owner.isBlank()) {
            return MsgOutcome211b.Ignored
        }
        val original = messages.find { it.id == op.messageId }
            ?.takeUnless { it.type == MessageType.REVOKED } ?: return MsgOutcome211b.Ignored
        val actorChoice = normalizedEmoji.takeUnless { candidate ->
            original.reactions.any { it.userId == c.owner && it.emoji == candidate }
        }
        val optimistic = original.copy(
            reactions = msgApplyReactions211b(
                original.reactions.map { msgReactionFrom211b(it) },
                c.owner,
                actorChoice,
                c.nowBase,
            ).map { it.toReaction() },
        )
        projectReaction(optimistic)
        return try {
            val commit = facadeCall(op, "REACTION_SET", original, optimistic, actorChoice)
            when (commit) {
                is MsgOutcome211b.Applied -> commit
                else -> commit
            }
        } catch (e: CancellationException) {
            rollbackActorChoice(op, original)
            throw e
        } catch (e: Exception) {
            rollbackActorChoice(op, original)
            MsgOutcome211b.Failed(e.javaClass.simpleName, e.message)
        }
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                if (!c.revokeEnabled) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.FEATURE_DISABLED)) }
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.SESSION_EXPIRED)) }
                } else {
                    launchBody { deleteOne(op, op.messageId) }
                }
            }
            1 -> {
                if (!c.revokeEnabled) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.FEATURE_DISABLED)) }
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.SESSION_EXPIRED)) }
                } else if (op.messageIds.isNotEmpty()) {
                    launchBody {
                        for (id in op.messageIds) {
                            try {
                                deleteOne(op, id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                logWarning("batch delete item failed: $id", e)
                            }
                        }
                    }
                }
            }
            2 -> {
                if (!c.revokeEnabled) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.FEATURE_DISABLED)) }
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.SESSION_EXPIRED)) }
                } else {
                    launchBody {
                        val original = messages.find { it.id == op.messageId } ?: return@launchBody
                        val revoked = original.toRevokedPlaceholder(
                            msgText211b(ChatMessageMutationText.MESSAGE_REVOKED_PLACEHOLDER),
                        )
                        val outcome = execute(
                            op, MessageMutationKind.REVOKE, original,
                            MessageMutationProjection.Set(revoked),
                            MessageMutationProjection.Set(original),
                            true, "REVOKE", revoked,
                        )
                        applyOutcome(outcome, op.messageId, msgText211b(ChatMessageMutationText.REVOKE_FAILED))
                    }
                }
            }
            3 -> {
                if (!c.editEnabled) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.FEATURE_DISABLED)) }
                } else {
                    val trimmed = op.newText.trim()
                    val original = messages.find { it.id == op.messageId }
                    if (original == null) {
                        // 不动。
                    } else if (trimmed.isBlank()) {
                        updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.EDIT_EMPTY)) }
                    } else if (
                        original.senderId != c.owner ||
                        original.type !in setOf(MessageType.TEXT, MessageType.MARKDOWN) ||
                        !msgWithinWindow211b(c.nowBase, original.timestamp)
                    ) {
                        updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.EDIT_NOT_ALLOWED)) }
                    } else if (c.token.isBlank() || c.owner.isBlank()) {
                        updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.SESSION_EXPIRED)) }
                    } else {
                        launchBody {
                            // parsedMeta 的结果只进 composeContentWithMeta；线束里它恒为
                            // "C:" + trimmed，种子正文又不含 <meta>，故 meta 不影响结果。
                            val optimistic = original.toOptimisticEdit("C:" + trimmed)
                            updateState { it.copy(groupEncryptionWarning = null) }
                            val outcome = execute(
                                op, MessageMutationKind.EDIT, original,
                                MessageMutationProjection.Set(optimistic),
                                MessageMutationProjection.Set(original),
                                false, "EDIT", optimistic,
                            )
                            applyOutcome(outcome, op.messageId, msgText211b(ChatMessageMutationText.EDIT_FAILED))
                        }
                    }
                }
            }
            else -> {
                if (!c.requireReactions) {
                    // 不动。
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    updateState { it.copy(groupEncryptionWarning = msgText211b(ChatMessageMutationText.SESSION_EXPIRED)) }
                } else {
                    launchBody {
                        when (val outcome = toggleReaction(op)) {
                            is MsgOutcome211b.Applied -> if (outcome.localError != null) {
                                updateState {
                                    it.copy(
                                        groupEncryptionWarning = msgText211b(
                                            ChatMessageMutationText.MUTATION_COMMITTED_LOCAL_SYNC_PENDING,
                                        ),
                                    )
                                }
                            }
                            is MsgOutcome211b.Failed -> updateState {
                                it.copy(
                                    groupEncryptionWarning = outcome.message
                                        ?: msgText211b(ChatMessageMutationText.REACTION_FAILED),
                                )
                            }
                            MsgOutcome211b.Ignored -> Unit
                        }
                    }
                }
            }
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun msgThrowPersist211b(op: MsgOp211b) {
    when (op.persistFault) {
        1 -> throw RuntimeException("boom")
        2 -> throw RuntimeException()
        3 -> throw CancellationException("cancel-boom")
    }
}

private fun msgProd211b(c: MsgCase211b): String {
    val effects = mutableListOf<String>()
    var state = ChatDetailUiState(
        messages = c.seedMessages.map { it.toMessage() },
        pinnedMessages = c.seedPins.map { it.toDto() },
        groupEncryptionWarning = c.seedWarning,
    )
    fun snap(prefix: String) {
        val snap = listOf(
            state.messages.joinToString(";") { msgMsgEnc211b(msgMsgFrom211b(it)) },
            state.pinnedMessages.joinToString(";") { msgPinEnc211b(msgPinFrom211b(it)) },
            opt211b(state.groupEncryptionWarning),
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
    var curOp = MsgOp211b(0)
    val facade = MessagingV2MutationFacade(
        eventOutbox = { conversationId, event, groupRevision ->
            effects += "CALL:enqueue:" + event.action + ":" + encStr211b(conversationId) + ":" +
                encStr211b(event.targetMessageId) + ":" + opt211b(event.content) + ":" +
                optLong211b(event.editedAt) + ":" + opt211b(event.reactionEmoji) + ":" +
                optLong211b(groupRevision)
            when (curOp.enqueueFault) {
                1 -> throw RuntimeException("boom")
                2 -> throw RuntimeException()
                3 -> throw CancellationException("cancel-boom")
            }
        },
        persistDeleted = { id ->
            effects += "CALL:persistDeleted:" + encStr211b(id)
            msgThrowPersist211b(curOp)
        },
        persistRevoked = { id, msg ->
            effects += "CALL:persistRevoked:" + encStr211b(id) + ":" + msgMsgEnc211b(msgMsgFrom211b(msg))
            msgThrowPersist211b(curOp)
        },
        persistEdited = { msg ->
            effects += "CALL:persistEdited:" + msgMsgEnc211b(msgMsgFrom211b(msg))
            msgThrowPersist211b(curOp)
            if (curOp.persistNull) null else msg
        },
        persistReaction = { original, reactions, actorUserId, emoji ->
            effects += "CALL:persistReaction:" + encStr211b(original.id) + ":" +
                msgReactionsEnc211b(reactions.map { msgReactionFrom211b(it) }) + ":" +
                encStr211b(actorUserId) + ":" + opt211b(emoji)
            msgThrowPersist211b(curOp)
        },
        indexMessage = { msg ->
            effects += "CALL:indexMsg:" + encStr211b(msg.id)
            if (curOp.indexFault == 1) throw RuntimeException("boom")
        },
        cleanupAttachment = { id ->
            effects += "CALL:cleanupAttach:" + encStr211b(id)
            if (curOp.cleanupFault == 1) throw RuntimeException("boom")
        },
        refreshConversationPreview = { chatId ->
            effects += "CALL:refreshPreview:" + encStr211b(chatId)
            if (curOp.refreshFault == 1) throw RuntimeException("boom")
        },
        isOwnerSessionCurrent = { c.sessOk },
        cleanupTerminalNotification = { msg ->
            effects += "CALL:cleanupNotif:" + encStr211b(msg.id)
            if (curOp.notifFault == 1) throw RuntimeException("boom")
        },
    )
    val mutationCoordinator = ConversationMessageMutationCoordinator(facade)
    val reactionCoordinator = ConversationReactionCoordinator(facade, clock = { c.nowBase })
    val controller = ChatMessageMutationController(
        scope = scope,
        ownerUserId = { c.owner },
        token = { c.token },
        currentState = { state },
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        text = { msgText211b(it) },
        currentGroupRevision = { c.groupRevision },
        composeContentWithMeta = { text, _ -> "C:" + text },
        requireReactions = { c.requireReactions },
        projectMessageMutation = { proj ->
            when (proj) {
                is MessageMutationProjection.Remove -> state = state.copy(
                    messages = state.messages.filterNot { it.id == proj.messageId },
                )
                is MessageMutationProjection.Set -> state = state.copy(
                    messages = state.messages.map { if (it.id == proj.message.id) proj.message else it },
                )
            }
            snap("ST")
        },
        mutationCoordinator = mutationCoordinator,
        reactionCoordinator = reactionCoordinator,
        messageRevokeEnabled = { c.revokeEnabled },
        messageEditEnabled = { c.editEnabled },
        logWarning = { msg, err ->
            effects += "WARN:" + encStr211b(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
        },
    )
    for (op in c.ops) {
        curOp = op
        try {
            when (op.kind) {
                0 -> controller.deleteMessage(op.messageId)
                1 -> controller.deleteMessagesBatch(op.messageIds)
                2 -> controller.revokeMessage(op.messageId)
                3 -> controller.editTextMessage(op.messageId, op.newText)
                else -> controller.setMessageReaction(op.messageId, op.emoji)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val MSG_IDS211b = listOf("m1", "m2", "m3")
private val MSG_CONTENTS211b = listOf("hello", "  spaced  ", "", "x", "multi word text", "a".repeat(50))
private val MSG_SENDERS211b = listOf("me", "u1", "u2")
private val MSG_TYPES211b = listOf("TEXT", "MARKDOWN", "IMAGE", "REVOKED")
private val MSG_EMOJIS211b = listOf("♥", ":)", "", "  ")

private fun randomMsgMeta211b(r: Random): MsgMeta211b = MsgMeta211b(
    mentions = if (r.nextInt(4) == 0) listOf("u1") else emptyList(),
    replyToId = if (r.nextInt(4) == 0) "m1" else null,
    aiAssisted = r.nextInt(4) == 0,
    aiAssistantMode = if (r.nextInt(4) == 0) "mode" else null,
    markdown = r.nextInt(4) == 0,
)

private fun randomMsgReaction211b(r: Random): MsgReaction211b = MsgReaction211b(
    userId = MSG_SENDERS211b[r.nextInt(MSG_SENDERS211b.size)],
    emoji = MSG_EMOJIS211b[r.nextInt(2)],
    reactedAt = listOf(500L, 1500L)[r.nextInt(2)],
)

private fun randomMsgMsg211b(r: Random, nowBase: Long, id: String): MsgMsg211b {
    val timestamp = when (r.nextInt(4)) {
        0 -> nowBase - 60_000L
        1 -> nowBase - 600_000L
        else -> listOf(1000L, 2000L)[r.nextInt(2)]
    }
    return MsgMsg211b(
        id = id,
        senderId = MSG_SENDERS211b[r.nextInt(MSG_SENDERS211b.size)],
        content = MSG_CONTENTS211b[r.nextInt(MSG_CONTENTS211b.size)],
        type = MSG_TYPES211b[r.nextInt(MSG_TYPES211b.size)],
        timestamp = timestamp,
        status = listOf("SENT", "DELIVERED", "READ")[r.nextInt(3)],
        editedAt = timestamp + 5L,
        starred = r.nextInt(4) == 0,
        meta = randomMsgMeta211b(r),
        reactions = List(r.nextInt(0, 3)) { randomMsgReaction211b(r) },
        expiresAt = if (r.nextInt(5) == 0) 9999L else null,
        sealedSender = r.nextInt(5) == 0,
    )
}

private fun randomMsgOp211b(r: Random): MsgOp211b = MsgOp211b(
    kind = r.nextInt(5),
    messageId = listOf("m1", "m2", "m3", "ghost", "")[r.nextInt(5)],
    messageIds = List(r.nextInt(0, 4)) { MSG_IDS211b[r.nextInt(MSG_IDS211b.size)] },
    newText = listOf("new text", "  ", "", "edited", "  padded  ")[r.nextInt(5)],
    emoji = MSG_EMOJIS211b[r.nextInt(MSG_EMOJIS211b.size)],
    enqueueFault = r.nextInt(4),
    persistFault = r.nextInt(4),
    cleanupFault = r.nextInt(2),
    notifFault = r.nextInt(2),
    refreshFault = r.nextInt(2),
    indexFault = r.nextInt(2),
    persistNull = r.nextInt(4) == 0,
)

private fun randomMsgCase211b(r: Random): MsgCase211b {
    val nowBase = System.currentTimeMillis()
    val n = r.nextInt(0, 4)
    return MsgCase211b(
        nowBase = nowBase,
        owner = listOf("me", "u1", "")[r.nextInt(3)],
        token = listOf("tok", "", " ")[r.nextInt(3)],
        groupRevision = listOf<Long?>(7L, null, 0L)[r.nextInt(3)],
        revokeEnabled = r.nextInt(5) != 0,
        editEnabled = r.nextInt(5) != 0,
        requireReactions = r.nextInt(5) != 0,
        sessOk = r.nextInt(5) != 0,
        seedMessages = (0 until n).map { randomMsgMsg211b(r, nowBase, MSG_IDS211b[it]) },
        seedPins = if (r.nextInt(3) == 0) {
            listOf(MsgPin211b("c1", "m1", pinnedAt = 100L))
        } else {
            emptyList()
        },
        seedWarning = if (r.nextInt(4) == 0) "W0" else null,
        ops = List(r.nextInt(0, 9)) { randomMsgOp211b(r) },
    )
}

private fun boundaryMsgCase211b(r: Random): MsgCase211b {
    val nowBase = System.currentTimeMillis()
    val seeds = listOf(
        MsgMsg211b("m1", senderId = "me", content = "hello", timestamp = nowBase - 60_000L, editedAt = nowBase - 59_995L),
        MsgMsg211b("m2", senderId = "u1", content = "old", type = "TEXT", timestamp = nowBase - 600_000L, editedAt = nowBase - 599_995L),
        MsgMsg211b("m3", senderId = "me", content = "pic", type = "IMAGE", timestamp = nowBase - 60_000L, editedAt = nowBase - 59_995L, reactions = listOf(MsgReaction211b("me", "♥", 500L))),
    )
    val pins = listOf(MsgPin211b("c1", "m1", pinnedAt = 100L))
    val base = MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins)
    return when (r.nextInt(30)) {
        0 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1")))
        1 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1", enqueueFault = 1)))
        2 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1", enqueueFault = 3)))
        3 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1", persistFault = 1)))
        4 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1", persistFault = 3)))
        5 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1")), sessOk = false)
        6 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "ghost")))
        7 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1")), token = "")
        8 -> base.copy(ops = listOf(MsgOp211b(0, messageId = "m1")), revokeEnabled = false)
        9 -> base.copy(ops = listOf(MsgOp211b(1, messageIds = listOf("m1", "m2"))))
        10 -> base.copy(ops = listOf(MsgOp211b(1, messageIds = listOf("m1", "ghost", "m2"), enqueueFault = 3)))
        11 -> base.copy(ops = listOf(MsgOp211b(1, messageIds = emptyList())))
        12 -> base.copy(ops = listOf(MsgOp211b(2, messageId = "m1")))
        13 -> base.copy(ops = listOf(MsgOp211b(2, messageId = "m1", enqueueFault = 1)))
        14 -> base.copy(ops = listOf(MsgOp211b(2, messageId = "m1", persistFault = 1, refreshFault = 1)))
        15 -> base.copy(ops = listOf(MsgOp211b(2, messageId = "m2")))
        16 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m1", newText = "edited")))
        17 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m2", newText = "edited")))
        18 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m3", newText = "edited")))
        19 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m1", newText = "   ")))
        20 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m1", newText = "edited", persistNull = true)))
        21 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m1", newText = "edited", indexFault = 1)))
        22 -> base.copy(ops = listOf(MsgOp211b(3, messageId = "m1", newText = "edited")), editEnabled = false)
        23 -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥")))
        24 -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m3", emoji = "♥")))
        25 -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "  ")))
        26 -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥", enqueueFault = 1)))
        27 -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥", persistFault = 1)))
        28 -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥", enqueueFault = 3)))
        else -> base.copy(ops = listOf(MsgOp211b(4, messageId = "m1", emoji = ":)")), requireReactions = false)
    }
}

// ---------- tests ----------

class ChatMessageMutationControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(211201)
        repeat(300) {
            val c = randomMsgCase211b(r)
            assertEquals(msgOracle211b(c), msgProd211b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(211202)
        repeat(200) {
            val c = boundaryMsgCase211b(r)
            assertEquals(msgOracle211b(c), msgProd211b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val nowBase = System.currentTimeMillis()
        val seeds = listOf(
            MsgMsg211b("m1", senderId = "me", content = "hello", timestamp = nowBase - 60_000L, editedAt = nowBase - 59_995L),
            MsgMsg211b("m2", senderId = "u1", content = "old", type = "TEXT", timestamp = nowBase - 600_000L, editedAt = nowBase - 599_995L),
        )
        val pins = listOf(MsgPin211b("c1", "m1", pinnedAt = 100L))
        val cases = listOf(
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(0, messageId = "m1"))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(0, messageId = "m1", enqueueFault = 1))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(0, messageId = "m1", enqueueFault = 3))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(0, messageId = "m1", persistFault = 1))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(0, messageId = "m1")), sessOk = false),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(1, messageIds = listOf("m1", "m2")))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(2, messageId = "m1"))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(2, messageId = "m1", persistFault = 1))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(3, messageId = "m1", newText = "edited"))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(3, messageId = "m2", newText = "edited"))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(3, messageId = "m1", newText = "edited", persistNull = true))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥"))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥", enqueueFault = 1))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = listOf(MsgOp211b(4, messageId = "m1", emoji = "♥", enqueueFault = 3))),
            MsgCase211b(nowBase = nowBase, seedMessages = seeds, seedPins = pins, ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(msgOracle211b(c), msgProd211b(c))
        }
    }
}
