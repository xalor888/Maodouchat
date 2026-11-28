package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMutationProjection
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr198c(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun encOpt198c(s: String?): String = if (s == null) "NULL" else "S:" + encStr198c(s)

// 注入的 merge：incoming 覆盖同 id，顺序是旧 survivors + incoming。
// 默认 merge（mergeMessageVersions）33 块已覆盖，这里只测控制器自身逻辑。
private fun merge198c(existing: List<Message>, incoming: List<Message>): List<Message> =
    existing.filterNot { e -> incoming.any { it.id == e.id } } + incoming

private val CONTROLLER198c = ChatTimelineStateController(::merge198c)

// 送达阶梯判定逐字重写（谓词本身 165 块已覆盖，这里只复用它的规则）。
private fun rank198c(s: MessageStatus): Int = when (s) {
    MessageStatus.SENDING -> 0
    MessageStatus.SENT -> 1
    MessageStatus.DELIVERED -> 2
    MessageStatus.READ -> 3
    MessageStatus.FAILED -> -1
}

private fun canAdvance198c(from: MessageStatus, next: MessageStatus): Boolean {
    if (from == next) return true
    if (next == MessageStatus.FAILED) return from == MessageStatus.SENDING
    if (from == MessageStatus.FAILED) {
        return next == MessageStatus.SENDING || next == MessageStatus.SENT ||
            next == MessageStatus.DELIVERED || next == MessageStatus.READ
    }
    return rank198c(next) > rank198c(from)
}

private data class Msg198c(
    val id: String,
    val status: Int = 1,
    val content: String = "x",
    val type: Int = 0,
    val ts: Long = 0L,
)

private data class State198c(
    val msgs: List<Msg198c> = emptyList(),
    val sep: String? = null,
    val more: Boolean = false,
    val ready: Boolean = false,
    val err: String? = null,
)

// op: merge/replace/mutate/init/status。
private data class TimelineCase198c(
    val op: String,
    val state: State198c = State198c(),
    val incoming: List<Msg198c> = emptyList(),
    val targetId: String = "",
    val transformKind: Int = 0,
    val transformContent: String = "",
    val transformStatus: Int = 0,
    val mutationKind: Int = 0,
    val mutationMsg: Msg198c? = null,
    val sepId: String? = null,
    val targetStatus: Int = 0,
)

private fun toMessage198c(m: Msg198c): Message = Message(
    id = m.id,
    chatId = "c",
    senderId = "s",
    content = m.content,
    type = MessageType.entries[m.type],
    timestamp = m.ts,
    status = MessageStatus.entries[m.status],
)

private fun fromMessage198c(m: Message): Msg198c =
    Msg198c(m.id, m.status.ordinal, m.content, m.type.ordinal, m.timestamp)

private fun toState198c(s: State198c): ChatDetailUiState = ChatDetailUiState(
    messages = s.msgs.map(::toMessage198c),
    unreadSeparatorId = s.sep,
    hasMoreOlderMessages = s.more,
    initialTimelineReady = s.ready,
    initialLoadError = s.err,
)

private fun fromState198c(s: ChatDetailUiState): State198c = State198c(
    msgs = s.messages.map(::fromMessage198c),
    sep = s.unreadSeparatorId,
    more = s.hasMoreOlderMessages,
    ready = s.initialTimelineReady,
    err = s.initialLoadError,
)

private fun encodeState198c(s: State198c): String {
    val msgs = s.msgs.joinToString(",") {
        "${encStr198c(it.id)}|${it.status}|${encStr198c(it.content)}|${it.type}|${it.ts}"
    }
    return "$msgs#${encOpt198c(s.sep)}#${s.more}#${s.ready}#${encOpt198c(s.err)}"
}

// oracle 侧的 merge 与生产注入的是同一条规则（incoming 覆盖同 id）。
private fun mergeFixture198c(existing: List<Msg198c>, incoming: List<Msg198c>): List<Msg198c> =
    existing.filterNot { e -> incoming.any { it.id == e.id } } + incoming

private fun timelineOracle198c(c: TimelineCase198c): String {
    val st = c.state
    val out = when (c.op) {
        "merge" -> st.copy(msgs = mergeFixture198c(st.msgs, c.incoming))
        "replace" -> st.copy(msgs = st.msgs.map { m ->
            if (m.id == c.targetId) {
                if (c.transformKind == 0) m.copy(content = c.transformContent)
                else m.copy(status = c.transformStatus)
            } else m
        })
        "mutate" -> if (c.mutationKind == 0) {
            st.copy(msgs = st.msgs.filterNot { it.id == c.targetId })
        } else {
            val m = c.mutationMsg!!
            if (st.msgs.any { it.id == m.id }) {
                st.copy(msgs = st.msgs.map { if (it.id == m.id) m else it })
            } else {
                st.copy(msgs = mergeFixture198c(st.msgs, listOf(m)))
            }
        }
        "init" -> st.copy(
            msgs = mergeFixture198c(st.msgs, c.incoming),
            sep = c.sepId,
            more = false,
            ready = true,
            err = null,
        )
        "status" -> st.copy(msgs = st.msgs.map { m ->
            if (m.id == c.targetId && canAdvance198c(MessageStatus.entries[m.status], MessageStatus.entries[c.targetStatus])) {
                m.copy(status = c.targetStatus)
            } else m
        })
        else -> throw IllegalArgumentException("unknown op ${c.op}")
    }
    return encodeState198c(out)
}

private fun timelineProd198c(c: TimelineCase198c): String {
    val st = toState198c(c.state)
    val out = when (c.op) {
        "merge" -> CONTROLLER198c.mergeIncoming(st, c.incoming.map(::toMessage198c))
        "replace" -> CONTROLLER198c.replaceMessage(st, c.targetId) { m ->
            if (c.transformKind == 0) m.copy(content = c.transformContent)
            else m.copy(status = MessageStatus.entries[c.transformStatus])
        }
        "mutate" -> if (c.mutationKind == 0) {
            CONTROLLER198c.applyMutation(st, MessageMutationProjection.Remove(c.targetId))
        } else {
            CONTROLLER198c.applyMutation(st, MessageMutationProjection.Set(toMessage198c(c.mutationMsg!!)))
        }
        "init" -> CONTROLLER198c.initialHistoryLoaded(
            st, c.incoming.map(::toMessage198c), c.sepId,
        )
        "status" -> CONTROLLER198c.updateStatus(
            st, c.targetId, MessageStatus.entries[c.targetStatus],
        )
        else -> throw IllegalArgumentException("unknown op ${c.op}")
    }
    return encodeState198c(fromState198c(out))
}

private val MSG_IDS198c = listOf("m1", "m2", "m3", "", "dup")
private val MSG_CONTENTS198c = listOf("", "x", "hello", "a b", "<meta>{}", "y".repeat(20))
private val MSG_TYPES198c = listOf(0, 0, 0, 2, 5)
private val MSG_TS198c = listOf(0L, 1L, 2L, 3L, 100L)

private fun randomMsg198c(r: Random): Msg198c = Msg198c(
    id = MSG_IDS198c[r.nextInt(MSG_IDS198c.size)],
    status = r.nextInt(5),
    content = MSG_CONTENTS198c[r.nextInt(MSG_CONTENTS198c.size)],
    type = MSG_TYPES198c[r.nextInt(MSG_TYPES198c.size)],
    ts = MSG_TS198c[r.nextInt(MSG_TS198c.size)],
)

private fun randomMsgs198c(r: Random): List<Msg198c> =
    List(r.nextInt(0, 5)) { randomMsg198c(r) }

private fun randomState198c(r: Random): State198c = State198c(
    msgs = randomMsgs198c(r),
    sep = if (r.nextInt(3) == 0) null else MSG_IDS198c[r.nextInt(MSG_IDS198c.size)],
    more = r.nextBoolean(),
    ready = r.nextBoolean(),
    err = if (r.nextInt(3) == 0) null else "err",
)

private fun randomTimelineCase198c(r: Random): TimelineCase198c {
    val state = randomState198c(r)
    return when (r.nextInt(5)) {
        0 -> TimelineCase198c("merge", state = state, incoming = randomMsgs198c(r))
        1 -> TimelineCase198c(
            "replace",
            state = state,
            targetId = MSG_IDS198c[r.nextInt(MSG_IDS198c.size)],
            transformKind = r.nextInt(2),
            transformContent = MSG_CONTENTS198c[r.nextInt(MSG_CONTENTS198c.size)],
            transformStatus = r.nextInt(5),
        )
        2 -> if (r.nextBoolean()) {
            TimelineCase198c(
                "mutate", state = state,
                targetId = MSG_IDS198c[r.nextInt(MSG_IDS198c.size)],
                mutationKind = 0,
            )
        } else {
            TimelineCase198c(
                "mutate", state = state,
                mutationKind = 1, mutationMsg = randomMsg198c(r),
            )
        }
        3 -> TimelineCase198c(
            "init", state = state, incoming = randomMsgs198c(r),
            sepId = if (r.nextInt(3) == 0) null else MSG_IDS198c[r.nextInt(MSG_IDS198c.size)],
        )
        else -> TimelineCase198c(
            "status",
            state = state,
            targetId = MSG_IDS198c[r.nextInt(MSG_IDS198c.size)],
            targetStatus = r.nextInt(5),
        )
    }
}

private fun boundaryTimelineCase198c(r: Random): TimelineCase198c {
    val dup = Msg198c("dup", r.nextInt(5), "x", 0, 1L)
    val state = State198c(
        msgs = List(r.nextInt(0, 4)) { dup },
        sep = "dup",
        more = true,
        ready = false,
        err = "err",
    )
    return when (r.nextInt(5)) {
        0 -> TimelineCase198c("merge", state = state, incoming = listOf(dup, randomMsg198c(r)))
        1 -> TimelineCase198c("replace", state = state, targetId = "dup", transformKind = r.nextInt(2), transformContent = "", transformStatus = r.nextInt(5))
        2 -> TimelineCase198c("mutate", state = state, targetId = "dup", mutationKind = r.nextInt(2), mutationMsg = dup)
        3 -> TimelineCase198c("init", state = state, incoming = listOf(dup), sepId = "dup")
        else -> TimelineCase198c("status", state = state, targetId = "dup", targetStatus = r.nextInt(5))
    }
}

// ---------- tests ----------

class ChatTimelineStateFuzzTest {

    @Test
    fun timelineDifferential198() {
        val r = Random(198301)
        repeat(300) {
            val c = randomTimelineCase198c(r)
            assertEquals(timelineOracle198c(c), timelineProd198c(c))
        }
    }

    @Test
    fun timelineBoundaryDifferential198() {
        val r = Random(198302)
        repeat(300) {
            val c = boundaryTimelineCase198c(r)
            assertEquals(timelineOracle198c(c), timelineProd198c(c))
        }
    }

    @Test
    fun timelinePinned198() {
        // 注入 merge 是 incoming 覆盖同 id：旧 m2 被新 m2 顶掉，顺序是 survivors + incoming。
        val base = State198c(
            msgs = listOf(Msg198c("m1", 1, "a"), Msg198c("m2", 1, "b")),
            sep = "m1", more = true, ready = false, err = "old",
        )
        val merged = timelineProd198c(TimelineCase198c("merge", state = base, incoming = listOf(Msg198c("m2", 2, "B"), Msg198c("m3", 1, "c"))))
        assertEquals(timelineOracle198c(TimelineCase198c("merge", state = base, incoming = listOf(Msg198c("m2", 2, "B"), Msg198c("m3", 1, "c")))), merged)
        assertEquals("006d0031|1|0061|0|0,006d0032|2|0042|0|0,006d0033|1|0063|0|0#S:006d0031#true#false#S:006f006c0064", merged)
        // replace 只动目标行；status 沿阶梯走、倒退不动；init 一次把三个标志位钉死。
        val replaced = timelineProd198c(TimelineCase198c("replace", state = base, targetId = "m2", transformKind = 0, transformContent = "changed"))
        assertEquals(timelineOracle198c(TimelineCase198c("replace", state = base, targetId = "m2", transformKind = 0, transformContent = "changed")), replaced)
        val regressed = timelineProd198c(TimelineCase198c("status", state = base, targetId = "m1", targetStatus = 0))
        assertEquals(timelineOracle198c(TimelineCase198c("status", state = base, targetId = "m1", targetStatus = 0)), regressed)
        val failed = timelineProd198c(TimelineCase198c("status", state = State198c(msgs = listOf(Msg198c("m1", 0, "a"))), targetId = "m1", targetStatus = 4))
        assertEquals(timelineOracle198c(TimelineCase198c("status", state = State198c(msgs = listOf(Msg198c("m1", 0, "a"))), targetId = "m1", targetStatus = 4)), failed)
        val inited = timelineProd198c(TimelineCase198c("init", state = base, incoming = listOf(Msg198c("m9", 1, "z")), sepId = "m9"))
        assertEquals(timelineOracle198c(TimelineCase198c("init", state = base, incoming = listOf(Msg198c("m9", 1, "z")), sepId = "m9")), inited)
        val removed = timelineProd198c(TimelineCase198c("mutate", state = base, targetId = "m1", mutationKind = 0))
        assertEquals(timelineOracle198c(TimelineCase198c("mutate", state = base, targetId = "m1", mutationKind = 0)), removed)
    }
}
