package com.maodouchat.ai

import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.util.EncryptedAttachmentCrypto
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 自动图片 OCR：识别图片里的文字，写入搜索索引（与手动「提取文字」共用同一服务端能力）。
 *
 * 触发点：
 * - App 启动后（登录态）静默扫描最新图片；
 * - 设置页开启 OCR 开关后立即跑一轮。
 *
 * 运行条件（任一不满足即整体跳过/中止）：
 * - 已登录；
 * - RuntimeFlags.AI_IMAGE_OCR 与 AI_ANALYZE_IMAGE 开启；
 * - [AiPrivacyPreferences.consentAccepted]（用户同意过 AI 处理——与手动 AI 入口一致）；
 * - [ImageOcrPreferences.isEnabled]（本机开关，默认开）。
 *
 * 其余约束：
 * - 仅处理 IMAGE 类型消息；GIF/VIDEO 帧不处理（与手动入口一致，UI 仅对 IMAGE 显示）；
 * - 密聊（secret chat）图片一律跳过：结果不应落到可搜索的本地索引；
 * - 已有 ocr 结果（parsedMeta().aiImageAnalyses["ocr"]）的消息跳过；
 * - 服务端要求用户级+聊天级 AI 均开启，isParticipant 校验由服务端完成；
 * - 单个运行轮次上限 OCR_IMAGES_PER_RUN 张，避免冷启动后台烧掉整日 AI 预算；
 * - 每张图上传压缩 Base64（1024px / q72，与手动入口一致）。
 */
class ImageOcrAutoIndexer(
    private val context: Context,
    private val database: AppDatabase
) {
    private val mutex = Mutex()

    /** 执行一轮自动 OCR；返回成功识别并写入索引的图片数量。 */
    suspend fun runOnce(): Int {
        if (!preconditionsMet()) return 0
        val token = TokenManager.getInstance(context).getToken()?.takeIf(String::isNotBlank) ?: return 0
        return mutex.withLock { scanOnce(token) }
    }

    private fun preconditionsMet(): Boolean {
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.AI_IMAGE_OCR)) return false
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.AI_ANALYZE_IMAGE)) return false
        if (!AiPrivacyPreferences.consentAccepted(context)) return false
        if (!ImageOcrPreferences.isEnabled(context)) return false
        return true
    }

    private suspend fun scanOnce(token: String): Int {
        val candidates = withContext(Dispatchers.IO) {
            database.messageDao().getImageMessages(OCR_SCAN_WINDOW).map { it.toDomain() }
        }
        val secretChatIds = secretChatIds()
        var processed = 0
        var succeeded = 0
        for (message in candidates) {
            currentCoroutineContext().ensureActive()
            if (processed >= OCR_IMAGES_PER_RUN) break
            // 秘聊会话的图片不自动 OCR：识别文本不应进入可搜索索引
            if (message.chatId in secretChatIds) continue
            if (alreadyOcrIndexed(message)) continue
            processed++
            if (ocrAndPersist(message, token)) succeeded++
        }
        return succeeded
    }

    private suspend fun secretChatIds(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { database.secretChatDao().listSecretChatIds() }.getOrDefault(emptyList()).toSet()
    }

    private fun alreadyOcrIndexed(message: Message): Boolean =
        message.parsedMeta().aiImageAnalyses.containsKey(OCR_MODE)

    private suspend fun ocrAndPersist(message: Message, token: String): Boolean {
        val ocrText = runCatching { downloadAndOcr(message, token) }.getOrNull()
            ?: return false
        if (ocrText.isBlank()) return false
        // 与手动 AI 分析一致：结果写入 meta.aiImageAnalyses["ocr"]（本地缓存 + 可同步），
        // 且立即重算搜索索引（AiMessageResultStore.commit 内部完成）。
        val plainText = message.parsedContent()
        val currentMeta = message.parsedMeta()
        val updatedMeta = currentMeta.copy(
            aiImageAnalyses = currentMeta.aiImageAnalyses + (OCR_MODE to ocrText),
            preferredImageAnalysisMode = OCR_MODE
        )
        val updated = message.copy(
            content = composeContentWithMeta(plainText, updatedMeta),
            meta = updatedMeta
        )
        return withContext(Dispatchers.IO) {
            runCatching { AiMessageResultStore(database).commit(operationId = null, message = updated) }
                .getOrDefault(false)
        }
    }

    private suspend fun downloadAndOcr(message: Message, token: String): String {
        val local = withContext(Dispatchers.IO) {
            ensureLocalImage(message, token)
        } ?: return ""
        val base64 = withContext(Dispatchers.IO) {
            runCatching {
                ImagePicker.uriToBase64(
                    context = context,
                    uri = Uri.parse(local.parsedContent()),
                    maxWidth = 1_024,
                    quality = 72
                )
            }.getOrNull()
        }
        if (base64.isNullOrBlank()) return ""
        return ApiService.analyzeImage(token, base64, OCR_MODE, message.chatId)
            .getOrNull()
            ?.takeIf { it.mode == OCR_MODE }
            ?.text
            ?.trim()
            ?.take(OCR_RESULT_MAX_CHARS)
            .orEmpty()
    }

    /**
     * 确保图片本地可读：本地缓存命中直接返回；否则按密文附件引用下载+解密到缓存。
     * 解密失败/引用缺失/非参与方均返回 null（静默跳过，不阻断整轮）。
     */
    private suspend fun ensureLocalImage(message: Message, token: String): Message? {
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

    /** 与 ChatDetailViewModel.composeContentWithMeta 等价的编码逻辑。 */
    private fun composeContentWithMeta(text: String, meta: com.maodouchat.data.model.MessageMeta): String {
        if (
            meta.mentions.isEmpty() &&
            meta.replyToId == null &&
            meta.voiceTranscript.isNullOrBlank() &&
            meta.voiceDurationMs == null &&
            meta.translations.isEmpty() &&
            meta.aiImageAnalyses.isEmpty() &&
            meta.aiFileAnalyses.isEmpty() &&
            meta.aiFileLastQuestion.isNullOrBlank() &&
            !meta.aiAssisted &&
            meta.fileName.isNullOrBlank() &&
            meta.fileMimeType.isNullOrBlank() &&
            meta.fileSizeBytes == null &&
            meta.attachmentId == null &&
            !meta.markdown &&
            !meta.viewOnce &&
            !meta.viewOnceOpened &&
            !meta.silent &&
            !meta.spoilerMedia &&
            !meta.spoilerRevealed
        ) return text
        val json = com.maodouchat.util.JsonFormat.encodeMessageMeta(meta)
        return text + com.maodouchat.data.model.Message.META_TAG_PREFIX + json + "</meta>"
    }

    private companion object {
        const val OCR_MODE = "ocr"
        const val OCR_RESULT_MAX_CHARS = 6_000
        const val OCR_IMAGES_PER_RUN = 10
        const val OCR_SCAN_WINDOW = 200
    }
}
