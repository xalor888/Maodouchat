package com.maodouchat.data.repository

import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.NoRecipientDevicesException
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.network.ApiService
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.JsonFormat
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 跨设备同步本机 AI 派生消息元数据。
 * 覆盖语音转写、翻译、图片分析和文件分析；复用 /api/ai/summary-sync 不透明 envelope 队列。
 */
class AiMessageMetaSyncRepository(
    private val messageRepo: MessageRepository,
    private val signalProtocol: SignalProtocol,
    private val database: AppDatabase
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pullMutex = Mutex()

    data class ImportedMessageMeta(
        val message: Message,
        val chatId: String
    )

    suspend fun push(
        token: String,
        userId: String,
        message: Message,
        liveToken: () -> String? = { token },
        liveUserId: () -> String? = { userId },
    ): Result<Int> {
        if (token.isBlank() || userId.isBlank()) return Result.success(0)
        val sessionGeneration = MaodouchatApp.currentSessionGeneration()
        val encryptToken = currentSessionToken(userId, sessionGeneration, liveToken, liveUserId)
            ?: return Result.success(0)
        val senderDeviceId = signalProtocol.getDeviceId()
        val meta = message.parsedMeta()
        val voiceTranscript = meta.voiceTranscript?.trim()?.takeIf(String::isNotBlank)?.take(2_000)
        val translations = sanitizeStringMap(meta.translations, maxEntries = 8, maxKey = 40, maxValue = 4_000)
        val preferred = meta.preferredTranslationLanguage
            ?.trim()
            ?.take(40)
            ?.takeIf { it.isNotBlank() && translations.containsKey(it) }
        val imageAnalyses = sanitizeStringMap(meta.aiImageAnalyses, maxEntries = 3, maxKey = 40, maxValue = 6_000)
        val preferredImageMode = meta.preferredImageAnalysisMode
            ?.trim()
            ?.take(40)
            ?.takeIf { it.isNotBlank() && imageAnalyses.containsKey(it) }
        val fileAnalyses = sanitizeStringMap(meta.aiFileAnalyses, maxEntries = 4, maxKey = 120, maxValue = 8_000)
        val preferredFileMode = meta.preferredFileAnalysisMode
            ?.trim()
            ?.take(40)
            ?.takeIf { it.isNotBlank() }
        val fileLastQuestion = meta.aiFileLastQuestion?.trim()?.takeIf(String::isNotBlank)?.take(500)
        if (
            voiceTranscript == null &&
            translations.isEmpty() &&
            imageAnalyses.isEmpty() &&
            fileAnalyses.isEmpty()
        ) return Result.success(0)

        val payload = AiMessageMetaSyncPayload(
            chatId = message.chatId,
            messageId = message.id,
            voiceTranscript = voiceTranscript,
            translations = translations,
            preferredTranslationLanguage = preferred,
            aiImageAnalyses = imageAnalyses,
            preferredImageAnalysisMode = preferredImageMode,
            aiFileAnalyses = fileAnalyses,
            preferredFileAnalysisMode = preferredFileMode,
            aiFileLastQuestion = fileLastQuestion,
            updatedAt = System.currentTimeMillis()
        )
        return try {
            val encrypted = signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = encryptToken,
                recipientIds = listOf(userId),
                plaintext = json.encodeToString(payload),
                payloadType = PAYLOAD_TYPE,
                includeCurrentUserDevices = true
            ).getOrThrow()
            val targetDeviceIds = encrypted.targets
                .asSequence()
                .filter { it.userId == userId && it.deviceId != senderDeviceId }
                .map { it.deviceId }
                .distinct()
                .take(MAX_TARGET_DEVICES)
                .toList()
            if (targetDeviceIds.isEmpty()) return Result.success(0)
            val uploadToken = currentSessionToken(userId, sessionGeneration, liveToken, liveUserId)
                ?: return Result.success(0)
            val uploaded = ApiService.uploadAiSummarySync(
                token = uploadToken,
                syncId = syncId(payload),
                senderDeviceId = senderDeviceId,
                targetDeviceIds = targetDeviceIds,
                envelope = encrypted.envelope
            ).throwIfCancellation()
            if (currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null) Result.success(0)
            else uploaded.map { it.stored }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: NoRecipientDevicesException) {
            Result.success(0)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun pull(
        token: String,
        userId: String,
        liveToken: () -> String? = { token },
        liveUserId: () -> String? = { userId },
    ): Result<List<ImportedMessageMeta>> {
        pullMutex.lock()
        try {
            if (token.isBlank() || userId.isBlank()) return Result.success(emptyList())
            val sessionGeneration = MaodouchatApp.currentSessionGeneration()
            val pullToken = currentSessionToken(userId, sessionGeneration, liveToken, liveUserId)
                ?: return Result.success(emptyList())
            val deviceId = signalProtocol.getDeviceId()
            val envelopes = ApiService.getPendingAiSummarySync(pullToken, deviceId, limit = 100)
                .throwIfCancellation()
                .getOrElse { return Result.failure(it) }
            if (currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null) {
                return Result.success(emptyList())
            }
            return try {
                val imported = mutableListOf<ImportedMessageMeta>()
                val acknowledgedIds = mutableListOf<String>()
                envelopes.forEach { remote ->
                    if (currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null) {
                        return@forEach
                    }
                    val payloadType = signalProtocol.envelopePayloadType(remote.envelope)
                    if (payloadType != PAYLOAD_TYPE) {
                        // 其他 payload（如摘要）留给对应仓库处理，不能误 ACK。
                        return@forEach
                    }
                    when (val decrypted = signalProtocol.decryptContentEnvelope(userId, remote.envelope)) {
                        is SignalProtocol.DecryptResult.Success -> {
                            val payload = runCatching {
                                json.decodeFromString(AiMessageMetaSyncPayload.serializer(), decrypted.plaintext)
                            }.getOrNull()
                            if (payload == null || !payload.isValid(remote.syncId)) {
                                acknowledgedIds += remote.id
                                return@forEach
                            }
                            val outcome = persistPayloadWhileCurrent(
                                payload = payload,
                                expectedUserId = userId,
                                expectedSessionGeneration = sessionGeneration,
                                liveToken = liveToken,
                                liveUserId = liveUserId
                            )
                            if (!outcome.acknowledge ||
                                currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null
                            ) {
                                return@forEach
                            }
                            outcome.imported?.let { merged ->
                                imported += ImportedMessageMeta(merged, merged.chatId)
                            }
                            acknowledgedIds += remote.id
                        }
                        SignalProtocol.DecryptResult.NoSession,
                        SignalProtocol.DecryptResult.UntrustedIdentity,
                        SignalProtocol.DecryptResult.Duplicate -> Unit
                        // 8.41：Failed 不 ACK（瞬时解密失败不得永久丢失跨设备数据），与 NoSession 一致重试
                        SignalProtocol.DecryptResult.UnsupportedEnvelope,
                        SignalProtocol.DecryptResult.NotForThisDevice,
                        SignalProtocol.DecryptResult.FutureEpoch -> acknowledgedIds += remote.id
                        SignalProtocol.DecryptResult.Failed -> Unit
                    }
                }
                acknowledgedIds.chunked(100).forEach { ids ->
                    val ackToken = currentSessionToken(userId, sessionGeneration, liveToken, liveUserId)
                        ?: return Result.success(emptyList())
                    ApiService.acknowledgeAiSummarySync(ackToken, deviceId, ids)
                        .throwIfCancellation()
                        .getOrThrow()
                    if (currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null) {
                        return Result.success(emptyList())
                    }
                }
                Result.success(imported)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        } finally {
            pullMutex.unlock()
        }
    }

    private suspend fun persistPayloadWhileCurrent(
        payload: AiMessageMetaSyncPayload,
        expectedUserId: String,
        expectedSessionGeneration: Long,
        liveToken: () -> String?,
        liveUserId: () -> String?
    ): MetaPersistOutcome {
        return database.withTransaction {
            if (currentSessionToken(expectedUserId, expectedSessionGeneration, liveToken, liveUserId) == null) {
                return@withTransaction MetaPersistOutcome.RETRY
            }
            val existing = messageRepo.getMessageById(payload.messageId)
                ?: return@withTransaction MetaPersistOutcome.RETRY
            if (currentSessionToken(expectedUserId, expectedSessionGeneration, liveToken, liveUserId) == null) {
                return@withTransaction MetaPersistOutcome.RETRY
            }
            if (existing.chatId != payload.chatId) {
                return@withTransaction MetaPersistOutcome.ACK_ONLY
            }
            val merged = mergeMessageMeta(existing, payload)
                ?: return@withTransaction MetaPersistOutcome.ACK_ONLY
            if (currentSessionToken(expectedUserId, expectedSessionGeneration, liveToken, liveUserId) == null) {
                return@withTransaction MetaPersistOutcome.RETRY
            }
            messageRepo.insertMessage(merged)
            if (currentSessionToken(expectedUserId, expectedSessionGeneration, liveToken, liveUserId) == null) {
                return@withTransaction MetaPersistOutcome.RETRY
            }
            try {
                MessageSearchRepository(database).indexMessage(merged)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // 索引失败不影响消息元数据持久化
            }
            if (currentSessionToken(expectedUserId, expectedSessionGeneration, liveToken, liveUserId) == null) {
                return@withTransaction MetaPersistOutcome.RETRY
            } else {
                MetaPersistOutcome(acknowledge = true, imported = merged)
            }
        }
    }

    private fun currentSessionToken(
        expectedUserId: String,
        expectedSessionGeneration: Long,
        liveToken: () -> String?,
        liveUserId: () -> String?
    ): String? {
        if (MaodouchatApp.currentSessionGeneration() != expectedSessionGeneration) return null
        if (liveUserId() != expectedUserId) return null
        val token = liveToken()?.takeIf { it.isNotBlank() } ?: return null
        return token.takeIf {
            MaodouchatApp.currentSessionGeneration() == expectedSessionGeneration &&
                BackgroundSessionGate.mayContinue(expectedUserId, token, liveUserId())
        }
    }

    private fun <T> Result<T>.throwIfCancellation(): Result<T> {
        val error = exceptionOrNull()
        if (error is kotlinx.coroutines.CancellationException) throw error
        return this
    }

    private data class MetaPersistOutcome(
        val acknowledge: Boolean,
        val imported: Message? = null
    ) {
        companion object {
            val RETRY = MetaPersistOutcome(acknowledge = false)
            val ACK_ONLY = MetaPersistOutcome(acknowledge = true)
        }
    }

    private fun mergeMessageMeta(existing: Message, payload: AiMessageMetaSyncPayload): Message? {
        val currentMeta = existing.parsedMeta()
        var changed = false

        var voiceTranscript = currentMeta.voiceTranscript
        val remoteVoice = payload.voiceTranscript?.trim()?.takeIf(String::isNotBlank)?.take(2_000)
        if (remoteVoice != null) {
            val localVoice = voiceTranscript?.trim().orEmpty()
            if (localVoice.isBlank() || remoteVoice.length >= localVoice.length) {
                if (voiceTranscript != remoteVoice) {
                    voiceTranscript = remoteVoice
                    changed = true
                }
            }
        }

        val mergedTranslations = currentMeta.translations.toMutableMap()
        changed = mergeStringMap(mergedTranslations, payload.translations, maxValue = 4_000) || changed

        var preferred = currentMeta.preferredTranslationLanguage
        val remotePreferred = payload.preferredTranslationLanguage?.trim()?.take(40)
            ?.takeIf { it.isNotBlank() && mergedTranslations.containsKey(it) }
        if (remotePreferred != null && preferred != remotePreferred) {
            preferred = remotePreferred
            changed = true
        }

        val mergedImageAnalyses = currentMeta.aiImageAnalyses.toMutableMap()
        changed = mergeStringMap(mergedImageAnalyses, payload.aiImageAnalyses, maxValue = 6_000) || changed
        var preferredImageMode = currentMeta.preferredImageAnalysisMode
        val remoteImageMode = payload.preferredImageAnalysisMode?.trim()?.take(40)
            ?.takeIf { it.isNotBlank() && mergedImageAnalyses.containsKey(it) }
        if (remoteImageMode != null && preferredImageMode != remoteImageMode) {
            preferredImageMode = remoteImageMode
            changed = true
        }

        val mergedFileAnalyses = currentMeta.aiFileAnalyses.toMutableMap()
        changed = mergeStringMap(mergedFileAnalyses, payload.aiFileAnalyses, maxValue = 8_000) || changed
        var preferredFileMode = currentMeta.preferredFileAnalysisMode
        val remoteFileMode = payload.preferredFileAnalysisMode?.trim()?.take(40)?.takeIf(String::isNotBlank)
        if (remoteFileMode != null && preferredFileMode != remoteFileMode) {
            preferredFileMode = remoteFileMode
            changed = true
        }
        var fileLastQuestion = currentMeta.aiFileLastQuestion
        val remoteFileQuestion = payload.aiFileLastQuestion?.trim()?.takeIf(String::isNotBlank)?.take(500)
        if (remoteFileQuestion != null && fileLastQuestion != remoteFileQuestion) {
            fileLastQuestion = remoteFileQuestion
            changed = true
        }

        if (!changed) return null
        val updatedMeta = currentMeta.copy(
            voiceTranscript = voiceTranscript,
            translations = mergedTranslations,
            preferredTranslationLanguage = preferred,
            aiImageAnalyses = mergedImageAnalyses,
            preferredImageAnalysisMode = preferredImageMode,
            aiFileAnalyses = mergedFileAnalyses,
            preferredFileAnalysisMode = preferredFileMode,
            aiFileLastQuestion = fileLastQuestion
        )
        return existing.copy(
            content = composeContentWithMeta(existing.parsedContent(), updatedMeta),
            meta = updatedMeta
        )
    }

    private fun mergeStringMap(
        target: MutableMap<String, String>,
        source: Map<String, String>,
        maxValue: Int
    ): Boolean {
        var changed = false
        source.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim().take(maxValue)
            if (key.isBlank() || value.isBlank()) return@forEach
            val existingValue = target[key]
            if (existingValue.isNullOrBlank() || value.length >= existingValue.length) {
                if (existingValue != value) {
                    target[key] = value
                    changed = true
                }
            }
        }
        return changed
    }

    private fun composeContentWithMeta(text: String, meta: MessageMeta): String {
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
            meta.attachmentId == null
        ) return text
        val encoded = JsonFormat.encode(
            mapOf(
                "mentions" to meta.mentions,
                "replyToId" to meta.replyToId,
                "voiceTranscript" to meta.voiceTranscript,
                "voiceDurationMs" to meta.voiceDurationMs,
                "translations" to meta.translations,
                "preferredTranslationLanguage" to meta.preferredTranslationLanguage,
                "aiImageAnalyses" to meta.aiImageAnalyses,
                "preferredImageAnalysisMode" to meta.preferredImageAnalysisMode,
                "aiFileAnalyses" to meta.aiFileAnalyses,
                "preferredFileAnalysisMode" to meta.preferredFileAnalysisMode,
                "aiFileLastQuestion" to meta.aiFileLastQuestion,
                "aiAssisted" to meta.aiAssisted,
                "aiAssistantMode" to meta.aiAssistantMode,
                "fileName" to meta.fileName,
                "fileMimeType" to meta.fileMimeType,
                "fileSizeBytes" to meta.fileSizeBytes,
                "attachmentId" to meta.attachmentId,
                "attachmentKeyBase64" to meta.attachmentKeyBase64,
                "attachmentIvBase64" to meta.attachmentIvBase64,
                "attachmentCipherSha256" to meta.attachmentCipherSha256,
                "attachmentPlainSha256" to meta.attachmentPlainSha256,
                "attachmentCipherSize" to meta.attachmentCipherSize
            )
        )
        return text + Message.META_TAG_PREFIX + encoded + "</meta>"
    }

    @Serializable
    private data class AiMessageMetaSyncPayload(
        val version: Int = PAYLOAD_VERSION,
        val chatId: String,
        val messageId: String,
        val voiceTranscript: String? = null,
        val translations: Map<String, String> = emptyMap(),
        val preferredTranslationLanguage: String? = null,
        val aiImageAnalyses: Map<String, String> = emptyMap(),
        val preferredImageAnalysisMode: String? = null,
        val aiFileAnalyses: Map<String, String> = emptyMap(),
        val preferredFileAnalysisMode: String? = null,
        val aiFileLastQuestion: String? = null,
        val updatedAt: Long
    ) {
        fun isValid(expectedSyncId: String): Boolean {
            val now = System.currentTimeMillis()
            val cleanTranslations = sanitizeStringMap(translations, maxEntries = 8, maxKey = 40, maxValue = 4_000)
            val cleanImage = sanitizeStringMap(aiImageAnalyses, maxEntries = 3, maxKey = 40, maxValue = 6_000)
            val cleanFile = sanitizeStringMap(aiFileAnalyses, maxEntries = 4, maxKey = 120, maxValue = 8_000)
            val cleanVoice = voiceTranscript?.trim()?.takeIf(String::isNotBlank)?.take(2_000)
            val cleanPreferred = preferredTranslationLanguage?.trim()?.take(40)
                ?.takeIf { it.isNotBlank() && cleanTranslations.containsKey(it) }
            val cleanPreferredImage = preferredImageAnalysisMode?.trim()?.take(40)
                ?.takeIf { it.isNotBlank() && cleanImage.containsKey(it) }
            val cleanPreferredFile = preferredFileAnalysisMode?.trim()?.take(40)?.takeIf(String::isNotBlank)
            val cleanFileQuestion = aiFileLastQuestion?.trim()?.takeIf(String::isNotBlank)?.take(500)
            val normalized = copy(
                voiceTranscript = cleanVoice,
                translations = cleanTranslations,
                preferredTranslationLanguage = cleanPreferred,
                aiImageAnalyses = cleanImage,
                preferredImageAnalysisMode = cleanPreferredImage,
                aiFileAnalyses = cleanFile,
                preferredFileAnalysisMode = cleanPreferredFile,
                aiFileLastQuestion = cleanFileQuestion
            )
            return version == PAYLOAD_VERSION &&
                syncId(normalized) == expectedSyncId &&
                chatId.isNotBlank() && chatId.length <= 100 &&
                messageId.isNotBlank() && messageId.length <= 100 &&
                (
                    cleanVoice != null ||
                        cleanTranslations.isNotEmpty() ||
                        cleanImage.isNotEmpty() ||
                        cleanFile.isNotEmpty()
                    ) &&
                updatedAt in 1L..(now + MAX_CLOCK_SKEW_MS)
        }
    }

    private companion object {
        const val PAYLOAD_VERSION = 2
        const val PAYLOAD_TYPE = "AI_MESSAGE_META_SYNC"
        const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1_000L
        const val MAX_TARGET_DEVICES = 20

        fun sanitizeStringMap(
            source: Map<String, String>,
            maxEntries: Int,
            maxKey: Int,
            maxValue: Int
        ): Map<String, String> {
            return source
                .mapNotNull { (language, text) ->
                    val key = language.trim().take(maxKey)
                    val value = text.trim().take(maxValue)
                    if (key.isBlank() || value.isBlank()) null else key to value
                }
                .toMap()
                .toSortedMap()
                .entries
                .take(maxEntries)
                .associate { it.key to it.value }
                .toSortedMap()
        }

        fun syncId(payload: AiMessageMetaSyncPayload): String {
            val unitSep = "\u0001"
            val pairSep = "\u0002"
            val fieldSep = "\u0000"
            fun mapPart(map: Map<String, String>): String =
                map.toSortedMap().entries.joinToString(unitSep) { it.key + pairSep + it.value }

            val material = listOf(
                payload.version.toString(),
                payload.chatId,
                payload.messageId,
                payload.voiceTranscript.orEmpty(),
                mapPart(payload.translations),
                payload.preferredTranslationLanguage.orEmpty(),
                mapPart(payload.aiImageAnalyses),
                payload.preferredImageAnalysisMode.orEmpty(),
                mapPart(payload.aiFileAnalyses),
                payload.preferredFileAnalysisMode.orEmpty(),
                payload.aiFileLastQuestion.orEmpty(),
                payload.updatedAt.toString()
            ).joinToString(fieldSep)
            return MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
