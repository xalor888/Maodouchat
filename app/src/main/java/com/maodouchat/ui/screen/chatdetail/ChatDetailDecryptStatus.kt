package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.crypto.DecryptPlaceholderPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

/**
 * 解密状态判定所需的最小协议能力（G328c 收窄端口）。
 *
 * 只声明这 4 个方法，而不是依赖整个 [com.maodouchat.crypto.SignalProtocol]：
 * 判定逻辑需要的是「这个 content 是不是信封」「这个失败是不是终局」这两类事实，
 * 收窄之后本类在 JVM 单测里可以只用一个假实现驱动，不必起 Android 环境。
 */
internal interface DecryptEnvelopeGate {
    fun isEncryptedEnvelope(content: String): Boolean
    fun isSenderKeyEnvelope(content: String): Boolean
    fun isDecryptTerminalFailure(senderId: String, content: String): Boolean
    fun isDecryptRetryExhausted(senderId: String, content: String): Boolean
}

/**
 * 解密失败相关的界面文案（G328c 注入化）。
 *
 * 为什么要传进来而不是在类里调 `context.getString`：原先这些判定是
 * `ChatDetailViewModel` 的成员，取文案要经 `getApplication()`，
 * 于是 `ChatDetailDecryptPolicyTest` 的注释里只能写「本机无 Robolectric 测不了」。
 * 注入之后同样的逻辑在纯 JVM 里可测（见 `ChatDetailDecryptStatusTest`）。
 */
internal data class DecryptTexts(
    val failed: String,
    val pending: String,
    val sessionMissing: String,
    val identityChanged: String,
    val groupFailed: String,
    val groupKeyMissing: String,
    val groupIdentityChanged: String,
    val groupNewer: String,
    val imageFailed: String,
    val gifFailed: String,
    val stickerFailed: String,
    val locationFailed: String,
    val videoFailed: String,
    val voiceFailed: String,
    val fileFailed: String,
)

/**
 * 「这条消息是不是解密失败占位符」「能不能跳过它继续同步」的判定（G328c 从
 * `ChatDetailViewModel` 抽出，纯搬移不改判断）。
 *
 * 这两条判定直接影响**同步能否推进**：把用户正常文本误判成占位符会让游标停住，
 * 把真正的失败判成普通消息则会让失败被当成内容渲染出去。原先它们埋在 3071 行的
 * ViewModel 里且无法单测，抽出来之后这些边界才有测试钉住。
 */
internal class ChatDetailDecryptStatus(
    private val gate: DecryptEnvelopeGate,
    private val texts: DecryptTexts,
) {

    /** 可解密的消息类型对应的失败文案；其余走通用失败文案。 */
    fun failedTextFor(type: MessageType): String = when (type) {
        MessageType.IMAGE -> texts.imageFailed
        MessageType.GIF -> texts.gifFailed
        MessageType.STICKER -> texts.stickerFailed
        MessageType.LOCATION -> texts.locationFailed
        MessageType.VIDEO -> texts.videoFailed
        MessageType.VOICE -> texts.voiceFailed
        MessageType.FILE -> texts.fileFailed
        else -> texts.failed
    }

    /**
     * Sync must not advance past recoverable decrypt failures (NoSession / identity / generic).
     * Placeholders are still shown in UI but the (ts,id) cursor stays so a later retry can re-fetch.
     */
    fun isSyncFailurePlaceholder(message: Message): Boolean {
        val content = message.content
        if (content.isBlank()) return false
        if (gate.isEncryptedEnvelope(content) || gate.isSenderKeyEnvelope(content)) return true
        return isKnownPlaceholder(message)
    }

    private fun isKnownPlaceholder(message: Message): Boolean =
        DecryptPlaceholderPolicy.isPlaceholder(
            message.content,
            texts.failed,
            texts.pending,
            texts.sessionMissing,
            texts.identityChanged,
            texts.groupFailed,
            texts.groupKeyMissing,
            texts.groupIdentityChanged,
            texts.groupNewer,
            failedTextFor(message.type),
        )

    /** Terminal/capped wires remain placeholders but no longer head-of-line block backlog sync. */
    fun canAdvancePastSyncFailure(message: Message): Boolean {
        val content = message.content
        if (!gate.isEncryptedEnvelope(content) && !gate.isSenderKeyEnvelope(content)) return false
        return gate.isDecryptTerminalFailure(message.senderId, content) ||
            gate.isDecryptRetryExhausted(message.senderId, content)
    }
}
