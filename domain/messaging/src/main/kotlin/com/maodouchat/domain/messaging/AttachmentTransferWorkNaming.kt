package com.maodouchat.domain.messaging

// 附件传输 WorkManager 命名簇：unique work 名构造、作业 tag 与 worker input key。
object AttachmentTransferWorkNaming {
    const val WORK_PREFIX = "attachment_transfer_"
    const val TAG = "attachment_transfer"

    const val KEY_MESSAGE_ID = "message_id"
    const val KEY_OWNER_USER_ID = "owner_user_id"

    private val jobIdUnsafeCharsRegex = Regex("[^A-Za-z0-9_-]")

    fun workName(ownerUserId: String, messageId: String): String = WORK_PREFIX +
        ownerUserId.replace(jobIdUnsafeCharsRegex, "_") + "_" +
        messageId.replace(jobIdUnsafeCharsRegex, "_")

    // worker 入参解析：消息 id 与归属用户任一缺失或空白即无法执行。
    fun resolveWorkerInput(messageId: String?, ownerUserId: String?): Pair<String, String>? {
        val id = messageId?.takeIf(String::isNotBlank) ?: return null
        val owner = ownerUserId?.takeIf(String::isNotBlank) ?: return null
        return id to owner
    }
}
