package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.MessagingV2GroupControlPolicy

/**
 * 发件箱发送/准备失败后的处置判定。纯逻辑：调用方按返回结果落库或改重试状态，
 * 这里不碰 DAO、不碰时钟。
 */
object OutboxFailurePolicy {
    /** 准备阶段排队了一个 Sender Key 信箱项时，发送失败的错误消息。 */
    const val SENDER_KEY_COVERAGE_PENDING = "sender_key_coverage_pending"

    /**
     * 群控消息（Sender Key 分发/密钥请求）撞上服务端群成员版本过期：这条已无法重发，
     * 直接丢弃，等新一轮准备重新取快照。
     */
    fun shouldDiscardStaleGroupControl(error: Exception, kind: String): Boolean =
        error is ApiException &&
            error.serverCode == "GROUP_REVISION_MISMATCH" &&
            MessagingV2GroupControlPolicy.isGroupControl(kind)

    /**
     * 服务端说设备覆盖或群成员版本对不上：密文是按旧覆盖集/旧版本加密的，
     * 重试必须回到准备态重新加密，而不是原样重发。
     */
    fun needsFreshEncryption(error: Exception): Boolean =
        error is ApiException &&
            error.serverCode in setOf("DEVICE_COVERAGE_MISMATCH", "GROUP_REVISION_MISMATCH")

    /**
     * 准备阶段刚排队了 Sender Key 信箱项：数据消息不用等指数退避，
     * 当前 flush 就能把那条先发出去，立刻重试即可。
     */
    fun isSenderKeyCoveragePending(errorMessage: String?): Boolean =
        errorMessage == SENDER_KEY_COVERAGE_PENDING
}
