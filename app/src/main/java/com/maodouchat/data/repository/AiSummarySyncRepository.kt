package com.maodouchat.data.repository

import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.NoRecipientDevicesException
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.dao.AiSummaryCacheDao
import com.maodouchat.data.local.entity.AiSummaryCacheEntity
import com.maodouchat.network.ApiService
import com.maodouchat.security.BackgroundSessionGate
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AiSummarySyncRepository(
    private val dao: AiSummaryCacheDao,
    private val signalProtocol: SignalProtocol
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pullMutex = Mutex()

    data class ImportedSummary(
        val entity: AiSummaryCacheEntity,
        val scope: String
    )

    suspend fun push(
        token: String,
        userId: String,
        summary: AiSummaryCacheEntity,
        scope: String,
        liveToken: () -> String? = { token },
        liveUserId: () -> String? = { userId },
    ): Result<Int> {
        if (token.isBlank() || userId.isBlank()) return Result.success(0)
        val sessionGeneration = MaodouchatApp.currentSessionGeneration()
        val encryptToken = currentSessionToken(userId, sessionGeneration, liveToken, liveUserId)
            ?: return Result.success(0)
        val senderDeviceId = signalProtocol.getDeviceId()
        val payload = AiSummarySyncPayload(
            cacheKey = summary.cacheKey,
            chatId = summary.chatId,
            startMessageId = summary.startMessageId,
            endMessageId = summary.endMessageId,
            messageCount = summary.messageCount,
            summary = summary.summary,
            scope = scope,
            createdAt = summary.createdAt
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
                syncId = syncId(summary.cacheKey, summary.summary),
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
    ): Result<List<ImportedSummary>> {
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
                val imported = mutableListOf<ImportedSummary>()
                val acknowledgedIds = mutableListOf<String>()
                envelopes.forEach { remote ->
                    if (currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null) {
                        return@forEach
                    }
                    if (signalProtocol.envelopePayloadType(remote.envelope) != PAYLOAD_TYPE) {
                        // 其他 payload（如消息 AI meta）留给对应仓库处理，不能误 ACK。
                        return@forEach
                    }
                    when (val decrypted = signalProtocol.decryptContentEnvelope(userId, remote.envelope)) {
                        is SignalProtocol.DecryptResult.Success -> {
                            val payload = runCatching {
                                json.decodeFromString(AiSummarySyncPayload.serializer(), decrypted.plaintext)
                            }.getOrNull()
                            if (payload != null && payload.isValid(remote.syncId)) {
                                val entity = AiSummaryCacheEntity(
                                    cacheKey = payload.cacheKey,
                                    chatId = payload.chatId,
                                    startMessageId = payload.startMessageId,
                                    endMessageId = payload.endMessageId,
                                    messageCount = payload.messageCount,
                                    summary = payload.summary,
                                    createdAt = payload.createdAt
                                )
                                when (
                                    dao.upsertIfNewerWhileCurrent(entity) {
                                        currentSessionToken(
                                            userId,
                                            sessionGeneration,
                                            liveToken,
                                            liveUserId
                                        ) != null
                                    }
                                ) {
                                    AiSummaryCacheDao.IMPORT_SKIPPED_SESSION_CHANGED -> return@forEach
                                    AiSummaryCacheDao.IMPORT_HANDLED -> Unit
                                    AiSummaryCacheDao.IMPORT_UPDATED -> {
                                        if (currentSessionToken(
                                                userId,
                                                sessionGeneration,
                                                liveToken,
                                                liveUserId
                                            ) == null
                                        ) {
                                            return@forEach
                                        }
                                        imported += ImportedSummary(entity, payload.scope)
                                    }
                                }
                            }
                            if (currentSessionToken(userId, sessionGeneration, liveToken, liveUserId) == null) {
                                return@forEach
                            }
                            acknowledgedIds += remote.id
                        }
                        SignalProtocol.DecryptResult.NoSession,
                        SignalProtocol.DecryptResult.UntrustedIdentity -> Unit
                        // Duplicate = 该信封已被消费（重复/乱序投递），ACK 避免服务端无限重投。
                        SignalProtocol.DecryptResult.Duplicate -> acknowledgedIds += remote.id
                        // 8.41：Failed 不再 ACK——瞬时解密失败（ratchet 在途等）跨设备数据永久丢失；
                        // 与 NoSession 一样重试。服务端 envelope 有 30 天保留期，不会无限堆积。
                        SignalProtocol.DecryptResult.UnsupportedEnvelope,
                        SignalProtocol.DecryptResult.NotForThisDevice,
                        SignalProtocol.DecryptResult.FutureEpoch,
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

    @Serializable
    private data class AiSummarySyncPayload(
        val version: Int = PAYLOAD_VERSION,
        val cacheKey: String,
        val chatId: String,
        val startMessageId: String,
        val endMessageId: String,
        val messageCount: Int,
        val summary: String,
        val scope: String,
        val createdAt: Long
    ) {
        fun isValid(expectedSyncId: String): Boolean {
            val now = System.currentTimeMillis()
            return version == PAYLOAD_VERSION &&
                cacheKey.isNotBlank() && cacheKey.length <= 500 &&
                syncId(cacheKey, summary) == expectedSyncId &&
                chatId.isNotBlank() && chatId.length <= 100 &&
                startMessageId.isNotBlank() && startMessageId.length <= 100 &&
                endMessageId.isNotBlank() && endMessageId.length <= 100 &&
                messageCount in 1..36 &&
                summary.isNotBlank() && summary.length <= 3_000 &&
                scope in ALLOWED_SCOPES &&
                createdAt in 1L..(now + MAX_CLOCK_SKEW_MS)
        }
    }

    private companion object {
        const val PAYLOAD_VERSION = 1
        const val PAYLOAD_TYPE = "AI_SUMMARY_SYNC"
        const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1_000L
        const val MAX_TARGET_DEVICES = 20
        val ALLOWED_SCOPES = setOf("RECENT", "TODAY", "SEVEN_DAYS", "THIRTY_DAYS", "SEARCH_RESULTS", "UNREAD")

        fun syncId(cacheKey: String, summary: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$cacheKey\u0000$summary".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
