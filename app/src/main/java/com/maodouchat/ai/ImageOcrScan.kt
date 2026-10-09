package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// 轮次扫描：拉取候选图片消息，逐张过门禁后交给识别簇。
object ImageOcrScan {
    suspend fun scanOnce(
        context: Context,
        database: AppDatabase,
        token: String,
        ownerUserId: String
    ): Int {
        val candidates = withContext(Dispatchers.IO) {
            database.messageDao().getImageMessages(ImageOcrAutoIndexer.OCR_SCAN_WINDOW).map { it.toDomain() }
        }
        val secretChatIds = secretChatIds(database) ?: return 0
        val lockedChatIds = withContext(Dispatchers.IO) {
            try {
                database.chatLockDao().listLockedChatIds().toSet()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
        }
        var processed = 0
        var succeeded = 0
        for (message in candidates) {
            currentCoroutineContext().ensureActive()
            // 每张图含网络往返与落库，循环内复查会话——登出/换号立即中止，
            // 防止旧账号图片密文继续上传、OCR 结果写进新账号/清库中的共享 DB。
            if (!ImageOcrGates.sessionGate(ownerUserId)) return succeeded
            if (processed >= ImageOcrAutoIndexer.OCR_IMAGES_PER_RUN) break
            // 秘聊 / 未解锁 PIN 会话的图片不自动 OCR：识别文本不应进入可搜索索引。
            if (message.chatId in secretChatIds) continue
            if (message.chatId in lockedChatIds &&
                !com.maodouchat.security.ChatLockSession.isUnlocked(message.chatId)
            ) continue
            if (alreadyOcrIndexed(message)) continue
            processed++
            if (ImageOcrRecognition.ocrAndPersist(context, database, message, token, ownerUserId)) succeeded++
        }
        return succeeded
    }

    private suspend fun secretChatIds(database: AppDatabase): Set<String>? = withContext(Dispatchers.IO) {
        try {
            database.chatDao().listSecretChatIds().toSet()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun alreadyOcrIndexed(message: Message): Boolean =
        message.parsedMeta().aiImageAnalyses.containsKey(ImageOcrAutoIndexer.OCR_MODE)
}
