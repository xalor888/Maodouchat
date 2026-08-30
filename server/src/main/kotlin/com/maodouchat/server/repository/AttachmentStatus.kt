package com.maodouchat.server.repository

/**
 * B07：加密附件状态机统一枚举。DB 仍存字符串，所有状态判断/写入统一经此枚举，
 * 避免路由、准入、上传会话与提交四处散落魔法字符串。
 */
enum class AttachmentStatus(val dbValue: String) {
    STAGED("STAGED"),
    UPLOADING("UPLOADING"),
    UPLOADED("UPLOADED"),
    COMMITTED("COMMITTED"),
    DELETED("DELETED"),
    QUARANTINED("QUARANTINED"),
}
