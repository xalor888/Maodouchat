package com.maodouchat.push

/**
 * P07：离线/保活唤醒载荷隐私门禁。
 *
 * FCM 已移除；契约仍锁定「唤醒只携带路由元数据，不得夹带聊天明文」。
 * 任意未来推送通道复用本策略，禁止 body/preview/content 等敏感键进入客户端处理路径。
 */
object PushWakePayloadPolicy {

    /** 允许出现在唤醒 data 中的非敏感键（小写比较）。 */
    val ALLOWED_KEYS: Set<String> = setOf(
        "type",
        "chatid",
        "messageid",
        "recipientid",
        "senderid",
        "callid",
        "calltype",
        "postid",
        "commentid",
        "actorid",
        "interaction",
        "inviteid",
        "fromuserid",
        "requestid",
        "action",
        "announcementid",
        "title",
        "level",
        "ts",
        "sig",
    )

    /** 明确禁止的聊天敏感键（小写）。命中即拒绝整包。 */
    val FORBIDDEN_SENSITIVE_KEYS: Set<String> = setOf(
        "body",
        "preview",
        "plaintext",
        "content",
        "message",
        "text",
        "ciphertext",
        "envelope",
        "lastmessage",
    )

    sealed interface Decision {
        data object Accept : Decision
        data class Reject(val reason: String) : Decision
    }

    fun evaluate(data: Map<String, String>): Decision {
        if (data.isEmpty()) return Decision.Reject("empty")
        val keys = data.keys.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val forbidden = keys.filter { it in FORBIDDEN_SENSITIVE_KEYS }
        if (forbidden.isNotEmpty()) {
            return Decision.Reject("sensitive_keys=${forbidden.sorted().joinToString(",")}")
        }
        val unknown = keys.filter { it !in ALLOWED_KEYS }
        if (unknown.isNotEmpty()) {
            return Decision.Reject("unknown_keys=${unknown.sorted().joinToString(",")}")
        }
        return Decision.Accept
    }

    fun isSafe(data: Map<String, String>): Boolean = evaluate(data) is Decision.Accept
}
