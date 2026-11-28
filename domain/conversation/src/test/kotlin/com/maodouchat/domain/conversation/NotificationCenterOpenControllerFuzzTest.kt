package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr205c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private data class NcoOp205c(
    val type: String,
    val deeplink: String?,
    val chatId: String?,
    val callId: String?,
)

private data class NcoCase205c(
    val hasSessionToken: Boolean = true,
    val ops: List<NcoOp205c> = emptyList(),
)

// 生产 parseLegacyCenterDeeplink 的镜像（只区分控制器分发关心的目标；
 // 其它目标一律落入 else -> None，与生产一致）。
private sealed interface NcoDest205c {
    data object MissedCallsTab : NcoDest205c
    data object ContactsTab : NcoDest205c
    data object GroupInvitesTab : NcoDest205c
    data class ChatDetail(val chatId: String) : NcoDest205c
    data class AiTasksChat(val chatId: String) : NcoDest205c
    data class PostDetail(val postId: String, val commentId: String?) : NcoDest205c
}

private fun sanitizeOpaque205c(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim().take(128)
    if (single.isBlank()) return null
    if (single.any { it.isWhitespace() || it.code < 0x20 }) return null
    return single
}

private fun sanitizeStrict205c(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaque205c(raw)

private fun parseLegacy205c(raw: String): NcoDest205c? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return when {
        value == "maodouchat:missed_calls" -> NcoDest205c.MissedCallsTab
        value == "maodouchat:contacts" -> NcoDest205c.ContactsTab
        value == "maodouchat:group_invites" -> NcoDest205c.GroupInvitesTab
        value.startsWith("maodouchat:chat:") -> {
            val id = value.removePrefix("maodouchat:chat:").substringBefore(':')
            NcoDest205c.ChatDetail(sanitizeStrict205c(id) ?: return null)
        }
        value.startsWith("maodouchat:ai_tasks:") -> {
            val id = value.removePrefix("maodouchat:ai_tasks:").substringBefore(':')
            NcoDest205c.AiTasksChat(sanitizeStrict205c(id) ?: return null)
        }
        value.startsWith("maodouchat:post:") -> {
            val payload = value.removePrefix("maodouchat:post:")
            val postRaw = payload.substringBefore('?').trim()
            val commentRaw = payload.substringAfter("?comment=", "").trim().takeIf { it.isNotBlank() }
            val postId = sanitizeStrict205c(postRaw) ?: return null
            val commentId = commentRaw?.let { sanitizeStrict205c(it) ?: return null }
            NcoDest205c.PostDetail(postId, commentId)
        }
        else -> null
    }
}

private fun encodeSegment205c(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            sb.append(ch)
        } else {
            for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                sb.append('%')
                sb.append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                sb.append("0123456789ABCDEF"[b.toInt() and 0xF])
            }
        }
    }
    return sb.toString()
}

private fun ncoOracleOp205c(c: NcoCase205c, op: NcoOp205c): String {
    val calls = mutableListOf<String>()
    fun outcome(): String {
        if (!c.hasSessionToken) return "OUT:GoLogin"
        if (op.type == "MESSAGE" && op.deeplink == null) {
            val chatId = op.chatId.orEmpty()
            if (chatId.isBlank()) return "OUT:None"
            calls += "CALL:clearMsg:" + encStr205c(chatId)
            return "OUT:Nav:chat_detail/" + encodeSegment205c(chatId)
        }
        if (op.type == "MISSED_CALL" && op.deeplink.isNullOrBlank()) {
            op.callId.orEmpty().takeIf { it.isNotBlank() }?.let { calls += "CALL:clearCall:" + encStr205c(it) }
            calls += "CALL:emitMissed"
            return "OUT:Pop"
        }
        if (op.type == "FRIEND_REQUEST" && op.deeplink.isNullOrBlank()) {
            calls += "CALL:emitContacts"
            return "OUT:Pop"
        }
        val dest = parseLegacy205c(op.deeplink.orEmpty()) ?: return "OUT:None"
        return when (dest) {
            is NcoDest205c.MissedCallsTab -> {
                op.callId.orEmpty().takeIf { it.isNotBlank() }?.let { calls += "CALL:clearCall:" + encStr205c(it) }
                calls += "CALL:emitMissed"
                "OUT:Pop"
            }
            is NcoDest205c.ContactsTab -> {
                calls += "CALL:emitContacts"
                "OUT:Pop"
            }
            is NcoDest205c.ChatDetail -> {
                calls += "CALL:clearMsg:" + encStr205c(dest.chatId)
                "OUT:Nav:chat_detail/" + encodeSegment205c(dest.chatId)
            }
            is NcoDest205c.AiTasksChat -> {
                calls += "CALL:clearAi:" + encStr205c(dest.chatId)
                "OUT:Nav:ai_tasks/" + encodeSegment205c(dest.chatId)
            }
            is NcoDest205c.PostDetail -> {
                calls += "CALL:clearPost:" + encStr205c(dest.postId)
                calls += "CALL:markPost:" + encStr205c(dest.postId)
                "OUT:Nav:post/" + encodeSegment205c(dest.postId) + "?comment=" + encodeSegment205c(dest.commentId.orEmpty())
            }
            is NcoDest205c.GroupInvitesTab -> "OUT:None"
        }
    }
    val out = outcome()
    return (calls + out).joinToString(",")
}

private fun ncoOracle205c(c: NcoCase205c): String {
    val effects = mutableListOf<String>()
    for (op in c.ops) {
        effects += "FIN:" + ncoOracleOp205c(c, op)
    }
    return effects.joinToString(",")
}

private fun ncoProd205c(c: NcoCase205c): String {
    val effects = mutableListOf<String>()
    val controller = NotificationCenterOpenController(
        hasSessionToken = { c.hasSessionToken },
        clearMessageTray = { effects += "CALL:clearMsg:" + encStr205c(it) },
        clearMissedCallTray = { effects += "CALL:clearCall:" + encStr205c(it) },
        clearAiTaskReminders = { effects += "CALL:clearAi:" + encStr205c(it) },
        clearPostInteractionTray = { effects += "CALL:clearPost:" + encStr205c(it) },
        markPostInteractionsRead = { effects += "CALL:markPost:" + encStr205c(it) },
        emitOpenMissedCalls = { effects += "CALL:emitMissed" },
        emitOpenContacts = { effects += "CALL:emitContacts" },
    )
    for (op in c.ops) {
        val mark = effects.size
        val extra = buildMap<String, String> {
            op.chatId?.let { put("chatId", it) }
            op.callId?.let { put("callId", it) }
        }
        val item = NotificationCenterItem(
            id = "n1",
            type = op.type,
            mergeKey = "k1",
            title = "t",
            deeplink = op.deeplink,
            extra = extra,
        )
        val outcome = controller.onOpenItem(item)
        val calls = effects.subList(mark, effects.size).toList()
        effects.subList(mark, effects.size).clear()
        val out = when (outcome) {
            is NotificationCenterOpenOutcome.Navigate -> "OUT:Nav:" + outcome.route
            NotificationCenterOpenOutcome.PopBackStack -> "OUT:Pop"
            NotificationCenterOpenOutcome.GoLogin -> "OUT:GoLogin"
            NotificationCenterOpenOutcome.None -> "OUT:None"
        }
        effects += "FIN:" + (calls + out).joinToString(",")
    }
    return effects.joinToString(",")
}

private val NCO_TYPES205c = listOf(
    "MESSAGE", "MISSED_CALL", "FRIEND_REQUEST", "AI_TASK",
    "POST_INTERACTION", "GROUP_INVITE", "REPORT", "SECURITY",
    "", "message", "MISSED_CALL ",
)
private val NCO_DEEPLINKS205c: List<String?> = listOf(
    null, "", " ", "maodouchat:missed_calls", "maodouchat:contacts",
    "maodouchat:group_invites", "maodouchat:chat:c1", "maodouchat:chat:c1:extra",
    "maodouchat:chat:", "maodouchat:chat:c1/evil", "maodouchat:chat:c1?x=1",
    "maodouchat:chat:  ", "maodouchat:chat:c 1", "maodouchat:chat:üñï",
    "maodouchat:ai_tasks:c9", "maodouchat:ai_tasks:",
    "maodouchat:post:p1", "maodouchat:post:p1?comment=cm2",
    "maodouchat:post:p1?comment=", "maodouchat:post:p1?x=1",
    "maodouchat:post:p1/p2", "maodouchat:post:p1?comment=c/m",
    "https://example.com/x", "maodouchat:u:bob", "MAODOUCHAT:CHAT:C1",
    "  maodouchat:chat:c1  ", "maodouchat:chat:x\ty",
)
private val NCO_IDS205c: List<String?> = listOf(null, "", " ", "c1", "call-1", "x\ty", "ünïcödé")

private fun randomNcoOp205c(r: Random): NcoOp205c = NcoOp205c(
    type = NCO_TYPES205c[r.nextInt(NCO_TYPES205c.size)],
    deeplink = NCO_DEEPLINKS205c[r.nextInt(NCO_DEEPLINKS205c.size)],
    chatId = NCO_IDS205c[r.nextInt(NCO_IDS205c.size)],
    callId = NCO_IDS205c[r.nextInt(NCO_IDS205c.size)],
)

private fun randomNcoCase205c(r: Random): NcoCase205c = NcoCase205c(
    hasSessionToken = r.nextInt(6) != 0,
    ops = List(r.nextInt(0, 9)) { randomNcoOp205c(r) },
)

private fun boundaryNcoCase205c(r: Random): NcoCase205c {
    val base = NcoCase205c()
    val cases = listOf(
        base.copy(ops = listOf(NcoOp205c("MESSAGE", null, "c-1", null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", null, null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", null, "   ", null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "", "c-1", null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", " ", "c-1", null))),
        base.copy(ops = listOf(NcoOp205c("MISSED_CALL", null, null, "call-1"))),
        base.copy(ops = listOf(NcoOp205c("MISSED_CALL", null, null, null))),
        base.copy(ops = listOf(NcoOp205c("MISSED_CALL", "", null, "call-1"))),
        base.copy(ops = listOf(NcoOp205c("MISSED_CALL", "maodouchat:missed_calls", null, "call-2"))),
        base.copy(ops = listOf(NcoOp205c("FRIEND_REQUEST", null, null, null))),
        base.copy(ops = listOf(NcoOp205c("FRIEND_REQUEST", "maodouchat:contacts", null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:c-9", null, null))),
        base.copy(ops = listOf(NcoOp205c("AI_TASK", "maodouchat:ai_tasks:c-7", null, null))),
        base.copy(ops = listOf(NcoOp205c("POST_INTERACTION", "maodouchat:post:p-3", null, null))),
        base.copy(ops = listOf(NcoOp205c("POST_INTERACTION", "maodouchat:post:p-3?comment=cm2", null, null))),
        base.copy(ops = listOf(NcoOp205c("REPORT", "maodouchat:group_invites", null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "https://example.com/x", "c-1", null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:c1/evil", "c-1", null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:c1:extra", null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:üñï", null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:post:p1?comment=cm2&x=1", null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:" + "a".repeat(130), null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:x\ty", null, null))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", null, "x\ty", null))),
        base.copy(ops = listOf(NcoOp205c("MISSED_CALL", null, null, "x\ty"))),
        base.copy(ops = listOf(NcoOp205c("message", "maodouchat:chat:c1", null, null))),
        base.copy(ops = listOf(NcoOp205c("MISSED_CALL ", null, null, "call-1"))),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", null, "c-1", null)), hasSessionToken = false),
        base.copy(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:c1", null, null), NcoOp205c("MISSED_CALL", null, null, "call-1"), NcoOp205c("FRIEND_REQUEST", null, null, null))),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class NotificationCenterOpenControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(205501)
        repeat(300) {
            val c = randomNcoCase205c(r)
            assertEquals(ncoOracle205c(c), ncoProd205c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(205502)
        repeat(200) {
            val c = boundaryNcoCase205c(r)
            assertEquals(ncoOracle205c(c), ncoProd205c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            NcoCase205c(ops = listOf(NcoOp205c("MESSAGE", null, "c-1", null))),
            NcoCase205c(ops = listOf(NcoOp205c("MESSAGE", null, "   ", null))),
            NcoCase205c(ops = listOf(NcoOp205c("MISSED_CALL", null, null, "call-1"))),
            NcoCase205c(ops = listOf(NcoOp205c("FRIEND_REQUEST", null, null, null))),
            NcoCase205c(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:c-9", null, null))),
            NcoCase205c(ops = listOf(NcoOp205c("AI_TASK", "maodouchat:ai_tasks:c-7", null, null))),
            NcoCase205c(ops = listOf(NcoOp205c("POST_INTERACTION", "maodouchat:post:p-3?comment=cm2", null, null))),
            NcoCase205c(ops = listOf(NcoOp205c("MESSAGE", "maodouchat:chat:c1/evil", "c-1", null))),
            NcoCase205c(ops = listOf(NcoOp205c("MESSAGE", null, "c-1", null)), hasSessionToken = false),
            NcoCase205c(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(ncoOracle205c(c), ncoProd205c(c))
        }
    }
}
