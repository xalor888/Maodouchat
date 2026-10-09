package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.model.Message
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
        val reference = toEncryptedAttachmentReference(message) ?: return null
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

    /** 与 ChatDetailViewModel 的私有实现等价：由 meta 字段构造解密附件引用。 */
    private fun toEncryptedAttachmentReference(message: Message): MediaCache.EncryptedAttachmentReference? {
        val meta = message.parsedMeta()
        val reference = MediaCache.EncryptedAttachmentReference(
            attachmentId = meta.attachmentId ?: return null,
            keyBase64 = meta.attachmentKeyBase64 ?: return null,
            ivBase64 = meta.attachmentIvBase64 ?: return null,
            cipherSha256 = meta.attachmentCipherSha256 ?: return null,
            plainSha256 = meta.attachmentPlainSha256 ?: return null,
            cipherSize = meta.attachmentCipherSize ?: return null,
            fileName = meta.fileName ?: return null,
            mimeType = meta.fileMimeType ?: "application/octet-stream",
            plainSize = meta.fileSizeBytes ?: return null,
            durationMs = meta.voiceDurationMs
        )
        return runCatching {
            MediaCache.decodeEncryptedAttachmentReference(MediaCache.encodeEncryptedAttachmentReference(reference))
        }.getOrNull()
    }

    /** 与 ChatDetailViewModel.composeContentWithMeta 等价：统一委托 JsonFormat 权威实现。 */
    fun composeContentWithMeta(text: String, meta: com.maodouchat.data.model.MessageMeta): String =
        com.maodouchat.util.JsonFormat.composeContentWithMeta(text, meta)
}
