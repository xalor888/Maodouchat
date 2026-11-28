package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr198a(s: String): String {
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

private fun encOpt198a(s: String?): String = if (s == null) "NULL" else "S:" + encStr198a(s)

private data class Msg198a(
    val id: String,
    val status: Int = 1,
    val content: String = "x",
    val type: Int = 0,
    val ts: Long = 0L,
)

private data class Transfer198a(
    val messageId: String,
    val state: String,
    val uploadedBytes: Long = 0L,
    val cipherSize: Long = 100L,
    val lastErrorCode: String? = null,
)

private data class MediaState198a(
    val msgs: List<Msg198a> = emptyList(),
    val progress: Map<String, Float> = emptyMap(),
    val states: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val downloading: Set<String> = emptySet(),
    val downloadErrors: Set<String> = emptySet(),
    val readyUri: String? = null,
    val warning: String? = null,
)

// op: apply/begin/finish/consume/segment。
private data class MediaCase198a(
    val op: String,
    val state: MediaState198a = MediaState198a(),
    val transfers: List<Transfer198a> = emptyList(),
    val targetId: String = "",
    val localUri: String? = null,
    val failureMessage: String? = null,
    val markFailure: Boolean = false,
    val completed: Long = 0,
    val total: Long = 0,
    val start: Float = 0f,
    val end: Float = 1f,
)

private val CONTROLLER198a = ChatMediaStateController()

// 传输进度映射逐字重写（谓词本身 38 块已覆盖，这里只复用它的规则）。
private fun uiProgress198a(t: Transfer198a): Float = when (t.state) {
    AttachmentTransferState.QUEUED -> 0.35f
    AttachmentTransferState.UPLOADING -> 0.35f +
        (t.uploadedBytes.toDouble() / t.cipherSize.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * 0.55f
    AttachmentTransferState.READY, AttachmentTransferState.SENDING -> 0.92f
    AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED ->
        0.35f + (t.uploadedBytes.toDouble() / t.cipherSize.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * 0.55f
    else -> 0f
}

private fun mapSegment198a(
    completed: Long,
    total: Long,
    start: Float,
    end: Float,
    previousPublished: Float,
): Float? {
    if (total <= 0L) return null
    val startClamped = start.coerceIn(0f, 1f)
    val endClamped = end.coerceIn(startClamped, 1f)
    val ratio = (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    val progressValue = (startClamped.toDouble() + ratio * (endClamped - startClamped)).coerceIn(0.0, 1.0)
    val progress = progressValue.toFloat()
    val prev = if (previousPublished < 0f) -1f else previousPublished.coerceIn(0f, 1f)
    if (prev >= 0f && kotlin.math.abs(prev.toDouble() - progressValue) < 0.01f.toDouble() && progress < 1f) {
        return null
    }
    return progress
}

private fun toMessage198a(m: Msg198a): Message = Message(
    id = m.id,
    chatId = "c",
    senderId = "s",
    content = m.content,
    type = MessageType.entries[m.type],
    timestamp = m.ts,
    status = MessageStatus.entries[m.status],
)

private fun fromMessage198a(m: Message): Msg198a =
    Msg198a(m.id, m.status.ordinal, m.content, m.type.ordinal, m.timestamp)

private fun toState198a(s: MediaState198a): ChatDetailUiState = ChatDetailUiState(
    messages = s.msgs.map(::toMessage198a),
    fileTransferProgress = s.progress,
    fileTransferStates = s.states,
    fileTransferErrors = s.errors,
    downloadingFileMessageIds = s.downloading,
    mediaDownloadErrorMessageIds = s.downloadErrors,
    fileReadyToOpenUri = s.readyUri,
    groupEncryptionWarning = s.warning,
)

private fun fromState198a(s: ChatDetailUiState): MediaState198a = MediaState198a(
    msgs = s.messages.map(::fromMessage198a),
    progress = s.fileTransferProgress,
    states = s.fileTransferStates,
    errors = s.fileTransferErrors,
    downloading = s.downloadingFileMessageIds,
    downloadErrors = s.mediaDownloadErrorMessageIds,
    readyUri = s.fileReadyToOpenUri,
    warning = s.groupEncryptionWarning,
)

private fun encodeState198a(s: MediaState198a): String {
    val msgs = s.msgs.joinToString(",") {
        "${encStr198a(it.id)}|${it.status}|${encStr198a(it.content)}|${it.type}|${it.ts}"
    }
    val progress = s.progress.entries.sortedBy { it.key }.joinToString(",") {
        "${encStr198a(it.key)}=${it.value.toBits()}"
    }
    val states = s.states.entries.sortedBy { it.key }.joinToString(",") {
        "${encStr198a(it.key)}=${encStr198a(it.value)}"
    }
    val errors = s.errors.entries.sortedBy { it.key }.joinToString(",") {
        "${encStr198a(it.key)}=${encStr198a(it.value)}"
    }
    val downloading = s.downloading.sorted().joinToString(",") { encStr198a(it) }
    val downloadErrors = s.downloadErrors.sorted().joinToString(",") { encStr198a(it) }
    return "$msgs#$progress#$states#$errors#$downloading#$downloadErrors#${encOpt198a(s.readyUri)}#${encOpt198a(s.warning)}"
}

private fun mediaOracle198a(c: MediaCase198a): String {
    val st = c.state
    val out = when (c.op) {
        "apply" -> {
            val byId = c.transfers.associateBy { it.messageId }
            val progress = (st.progress - st.states.keys).toMutableMap()
            val states = mutableMapOf<String, String>()
            val errors = mutableMapOf<String, String>()
            c.transfers.forEach { t ->
                progress[t.messageId] = uiProgress198a(t)
                states[t.messageId] = t.state
                t.lastErrorCode?.let { errors[t.messageId] = it }
            }
            st.copy(
                msgs = st.msgs.map { m ->
                    when (byId[m.id]?.state) {
                        AttachmentTransferState.FAILED -> m.copy(status = 4)
                        AttachmentTransferState.QUEUED,
                        AttachmentTransferState.UPLOADING,
                        AttachmentTransferState.READY,
                        AttachmentTransferState.SENDING,
                        AttachmentTransferState.PAUSED -> m.copy(status = 0)
                        else -> m
                    }
                },
                progress = progress,
                states = states,
                errors = errors,
            )
        }
        "begin" -> st.copy(
            downloading = st.downloading + c.targetId,
            downloadErrors = st.downloadErrors - c.targetId,
            progress = st.progress + (c.targetId to 0f),
            warning = null,
        )
        "finish" -> st.copy(
            downloading = st.downloading - c.targetId,
            downloadErrors = if (c.markFailure) st.downloadErrors + c.targetId else st.downloadErrors - c.targetId,
            progress = st.progress - c.targetId,
            readyUri = c.localUri ?: st.readyUri,
            warning = c.failureMessage ?: st.warning,
        )
        "consume" -> st.copy(readyUri = null)
        "segment" -> {
            val mapped = mapSegment198a(
                c.completed, c.total, c.start, c.end,
                st.progress[c.targetId] ?: -1f,
            ) ?: return encodeState198a(st)
            st.copy(progress = st.progress + (c.targetId to mapped))
        }
        else -> throw IllegalArgumentException("unknown op ${c.op}")
    }
    return encodeState198a(out)
}

private fun mediaProd198a(c: MediaCase198a): String {
    val st = toState198a(c.state)
    val out = when (c.op) {
        "apply" -> CONTROLLER198a.applyTransfers(
            st,
            c.transfers.map {
                AttachmentTransferSnapshot(
                    messageId = it.messageId,
                    state = it.state,
                    uploadedBytes = it.uploadedBytes,
                    cipherSize = it.cipherSize,
                    lastErrorCode = it.lastErrorCode,
                )
            },
        )
        "begin" -> CONTROLLER198a.beginDownload(st, c.targetId)
        "finish" -> CONTROLLER198a.finishDownload(
            st, c.targetId, c.localUri, c.failureMessage, c.markFailure,
        )
        "consume" -> CONTROLLER198a.consumeReadyFile(st)
        "segment" -> CONTROLLER198a.updateSegmentProgress(
            st, c.targetId, c.completed, c.total, c.start, c.end,
        )
        else -> throw IllegalArgumentException("unknown op ${c.op}")
    }
    return encodeState198a(fromState198a(out))
}

private val MEDIA_IDS198a = listOf("m1", "m2", "m3", "", "dup")
private val MEDIA_CONTENTS198a = listOf("", "x", "hello", "a b", "<meta>{}")
private val MEDIA_STATES198a = listOf(
    "PREPARING", "QUEUED", "UPLOADING", "READY", "SENDING", "PAUSED", "FAILED",
    "", "weird", "queued",
)
private val MEDIA_BYTES198a = listOf(0L, 1L, 50L, 99L, 100L, 101L, 1000L, -5L, 1L shl 40)
private val MEDIA_FLOATS198a = listOf(0f, 0.35f, 0.5f, 0.92f, 1f, -1f, 2f, 0.001f, 0.999f)
private val MEDIA_ERRS198a = listOf("E_TIMEOUT", "E_HASH", "", "x")

private fun randomMsg198a(r: Random): Msg198a = Msg198a(
    id = MEDIA_IDS198a[r.nextInt(MEDIA_IDS198a.size)],
    status = r.nextInt(5),
    content = MEDIA_CONTENTS198a[r.nextInt(MEDIA_CONTENTS198a.size)],
    type = r.nextInt(3),
    ts = r.nextInt(0, 4).toLong(),
)

private fun randomTransfer198a(r: Random): Transfer198a = Transfer198a(
    messageId = MEDIA_IDS198a[r.nextInt(MEDIA_IDS198a.size)],
    state = MEDIA_STATES198a[r.nextInt(MEDIA_STATES198a.size)],
    uploadedBytes = MEDIA_BYTES198a[r.nextInt(MEDIA_BYTES198a.size)],
    cipherSize = MEDIA_BYTES198a[r.nextInt(MEDIA_BYTES198a.size)],
    lastErrorCode = if (r.nextInt(3) == 0) null else MEDIA_ERRS198a[r.nextInt(MEDIA_ERRS198a.size)],
)

private fun randomMediaState198a(r: Random): MediaState198a {
    val ids = MEDIA_IDS198a.shuffled(r).take(r.nextInt(0, 4))
    return MediaState198a(
        msgs = List(r.nextInt(0, 5)) { randomMsg198a(r) },
        progress = ids.associateWith { MEDIA_FLOATS198a[r.nextInt(MEDIA_FLOATS198a.size)] },
        states = ids.associateWith { MEDIA_STATES198a[r.nextInt(MEDIA_STATES198a.size)] },
        errors = ids.filter { r.nextBoolean() }.associateWith { MEDIA_ERRS198a[r.nextInt(MEDIA_ERRS198a.size)] },
        downloading = ids.filter { r.nextBoolean() }.toSet(),
        downloadErrors = ids.filter { r.nextBoolean() }.toSet(),
        readyUri = if (r.nextInt(3) == 0) null else "file:///tmp/x",
        warning = if (r.nextInt(3) == 0) null else "warn",
    )
}

private fun randomMediaCase198a(r: Random): MediaCase198a {
    val state = randomMediaState198a(r)
    return when (r.nextInt(5)) {
        0 -> MediaCase198a("apply", state = state, transfers = List(r.nextInt(0, 4)) { randomTransfer198a(r) })
        1 -> MediaCase198a("begin", state = state, targetId = MEDIA_IDS198a[r.nextInt(MEDIA_IDS198a.size)])
        2 -> MediaCase198a(
            "finish", state = state,
            targetId = MEDIA_IDS198a[r.nextInt(MEDIA_IDS198a.size)],
            localUri = if (r.nextInt(3) == 0) null else "file:///tmp/y",
            failureMessage = if (r.nextInt(3) == 0) null else "fail",
            markFailure = r.nextBoolean(),
        )
        3 -> MediaCase198a("consume", state = state)
        else -> MediaCase198a(
            "segment", state = state,
            targetId = MEDIA_IDS198a[r.nextInt(MEDIA_IDS198a.size)],
            completed = MEDIA_BYTES198a[r.nextInt(MEDIA_BYTES198a.size)],
            total = MEDIA_BYTES198a[r.nextInt(MEDIA_BYTES198a.size)],
            start = MEDIA_FLOATS198a[r.nextInt(MEDIA_FLOATS198a.size)],
            end = MEDIA_FLOATS198a[r.nextInt(MEDIA_FLOATS198a.size)],
        )
    }
}

private fun boundaryMediaCase198a(r: Random): MediaCase198a {
    val dup = Msg198a("dup", 1, "x", 0, 1L)
    val state = MediaState198a(
        msgs = List(r.nextInt(0, 4)) { dup },
        progress = mapOf("dup" to MEDIA_FLOATS198a[r.nextInt(MEDIA_FLOATS198a.size)]),
        states = mapOf("dup" to "UPLOADING"),
        errors = mapOf("dup" to "E_TIMEOUT"),
        downloading = setOf("dup"),
        downloadErrors = setOf("dup"),
        readyUri = "file:///tmp/x",
        warning = "warn",
    )
    return when (r.nextInt(5)) {
        0 -> MediaCase198a(
            "apply", state = state,
            transfers = listOf(
                Transfer198a("dup", "UPLOADING", 150L, 100L, null),
                Transfer198a("dup", "FAILED", 0L, 0L, "E_HASH"),
            ),
        )
        1 -> MediaCase198a("begin", state = state, targetId = "dup")
        2 -> MediaCase198a("finish", state = state, targetId = "dup", localUri = null, failureMessage = "f", markFailure = true)
        3 -> MediaCase198a("consume", state = state)
        else -> MediaCase198a(
            "segment", state = state, targetId = "dup",
            completed = r.nextInt(0, 101).toLong(), total = 100L,
            start = 0f, end = 1f,
        )
    }
}

// ---------- tests ----------

class ChatMediaStateFuzzTest {

    @Test
    fun mediaDifferential198() {
        val r = Random(198101)
        repeat(300) {
            val c = randomMediaCase198a(r)
            assertEquals(mediaOracle198a(c), mediaProd198a(c))
        }
    }

    @Test
    fun mediaBoundaryDifferential198() {
        val r = Random(198102)
        repeat(300) {
            val c = boundaryMediaCase198a(r)
            assertEquals(mediaOracle198a(c), mediaProd198a(c))
        }
    }

    @Test
    fun mediaPinned198() {
        // apply：FAILED 钉死消息状态、QUEUED 改 SENDING、进度按 uiProgress 算。
        val base = MediaState198a(
            msgs = listOf(Msg198a("m1", 1, "a"), Msg198a("m2", 1, "b")),
            progress = mapOf("m1" to 0.5f),
            states = mapOf("m1" to "UPLOADING"),
        )
        val applied = mediaProd198a(MediaCase198a(
            "apply", state = base,
            transfers = listOf(
                Transfer198a("m1", "FAILED", 0L, 100L, "E_HASH"),
                Transfer198a("m2", "QUEUED", 0L, 100L, null),
            ),
        ))
        assertEquals(mediaOracle198a(MediaCase198a(
            "apply", state = base,
            transfers = listOf(
                Transfer198a("m1", "FAILED", 0L, 100L, "E_HASH"),
                Transfer198a("m2", "QUEUED", 0L, 100L, null),
            ),
        )), applied)
        // begin/finish/consume 三步走通下载生命周期；segment 半程映射到 0.5。
        val begun = mediaProd198a(MediaCase198a("begin", state = base, targetId = "m1"))
        assertEquals(mediaOracle198a(MediaCase198a("begin", state = base, targetId = "m1")), begun)
        val finished = mediaProd198a(MediaCase198a("finish", state = base, targetId = "m1", localUri = "file:///tmp/y", markFailure = false))
        assertEquals(mediaOracle198a(MediaCase198a("finish", state = base, targetId = "m1", localUri = "file:///tmp/y", markFailure = false)), finished)
        val consumed = mediaProd198a(MediaCase198a("consume", state = base.copy(readyUri = "file:///tmp/y")))
        assertEquals(mediaOracle198a(MediaCase198a("consume", state = base.copy(readyUri = "file:///tmp/y"))), consumed)
        val segmented = mediaProd198a(MediaCase198a("segment", state = base, targetId = "m1", completed = 50L, total = 100L, start = 0f, end = 1f))
        assertEquals(mediaOracle198a(MediaCase198a("segment", state = base, targetId = "m1", completed = 50L, total = 100L, start = 0f, end = 1f)), segmented)
        val invalid = mediaProd198a(MediaCase198a("segment", state = base, targetId = "m1", completed = 50L, total = 0L, start = 0f, end = 1f))
        assertEquals(mediaOracle198a(MediaCase198a("segment", state = base, targetId = "m1", completed = 50L, total = 0L, start = 0f, end = 1f)), invalid)
    }
}
