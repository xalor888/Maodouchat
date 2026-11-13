package com.maodouchat.ai

import android.content.Context
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.JsonFormat
import com.maodouchat.core.model.Message
import com.maodouchat.domain.messaging.toEncryptedAttachmentReference
import com.maodouchat.network.ApiService
import com.maodouchat.util.EncryptedAttachmentCrypto
import com.maodouchat.util.MediaCache

// 本地图片获取：缓存命中直接返回，否则按密文附件引用下载+解密到缓存。
object ImageOcrLocalImage {
    /**
     * 确保图片本地可读。解密失败/引用缺失/非参与方返回 null（静默跳过，不阻断整轮）。
     */
    suspend fun ensureLocalImage(context: Context, message: Message, token: String): Message? {
        if (MediaCache.isReadableLocalUri(context, message.parsedContent())) return message
        val reference = message.toEncryptedAttachmentReference() ?: return null
        return try {
            val target = MediaCache.createAttachmentCacheFile(context, message.id, reference.fileName, secretChatId = null)
            if (!EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference)) {
                val encrypted = MediaCache.createEncryptedDownloadFile(context, reference.attachmentId, message.id)
                try {
                    val ok = ApiService.downloadEncryptedAttachment(
                        token = token,
                        attachmentId = reference.attachmentId,
                        expectedSha256 = reference.cipherSha256,
                        expectedSize = reference.cipherSize,
                        target = encrypted
                    ).getOrNull() != null
                    if (!ok) return null
                    EncryptedAttachmentCrypto.decrypt(encrypted, target, reference)
                } finally {
                    encrypted.delete()
                }
            }
            message.copy(content = composeContentWithMeta(target.toURI().toString(), message.parsedMeta()))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /** 与 ChatDetailViewModel.composeContentWithMeta 等价：统一委托 JsonFormat 权威实现。 */
    fun composeContentWithMeta(text: String, meta: MessageMeta): String =
        JsonFormat.composeContentWithMeta(text, meta)
}
