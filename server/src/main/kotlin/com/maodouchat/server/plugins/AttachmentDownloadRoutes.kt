package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.UUID

/** 附件直传 / 下载（含 Range）/ 删除。 */
internal fun Route.configureEncryptedAttachmentDownloadRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    authenticate("auth-jwt") {


        post("/api/attachments") {
            val userId = call.requireUserId()
            if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
            val chatId = parseRawOrEmpty(call.request.queryParameters, "chatId")
            val pendingMessageId = parseRawOrEmpty(call.request.queryParameters, "messageId")
            val expectedHash = call.request.header(ATTACHMENT_HASH_HEADER)?.lowercase().orEmpty()
            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (chatId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                return@post
            }
            if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                return@post
            }
            if (conversationQueryRepo.getById(chatId)?.isGroup == true && conversationParticipantRepo.isMuted(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                return@post
            }
            if (!CLIENT_MESSAGE_ID_REGEX.matches(pendingMessageId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件消息 ID 无效"))
                return@post
            }
            if (call.request.contentType().withoutParameters() != ContentType.Application.OctetStream) {
                call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("附件必须使用二进制上传"))
                return@post
            }
            if (!expectedHash.matches(sha256HexRegex)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件哈希无效"))
                return@post
            }
            if (declaredLength == null || declaredLength !in 17L..MAX_ATTACHMENT_CIPHER_BYTES) {
                call.respond(ATTACHMENT_TOO_LARGE_STATUS, ErrorResponse("附件大小无效或超过限制"))
                return@post
            }
            if (!encryptedAttachmentRepo.hasCapacityFor(userId, chatId, pendingMessageId, declaredLength, maxAttachmentUserBytes)) {
                call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                return@post
            }
            if (!rateLimiter.acquire("attachment_upload:$userId", maxPerMinute = 20)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件上传过于频繁"))
                return@post
            }
            val attachmentId = "att_${UUID.randomUUID().toString().replace("-", "")}" 
            val tempFile = BlobStore.createTempFile(attachmentId)
            val received = try {
                call.receiveEncryptedAttachment(tempFile, MAX_ATTACHMENT_CIPHER_BYTES)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                BlobStore.delete(attachmentId)
                throw cancelled
            } catch (error: Throwable) {
                BlobStore.delete(attachmentId)
                call.application.log.warn("Encrypted attachment receive failed", error)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件上传中断"))
                return@post
            }
            if (received == null || received.byteCount != declaredLength || received.sha256 != expectedHash) {
                BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件长度或哈希校验失败"))
                return@post
            }
            val expiresAt = System.currentTimeMillis() + ATTACHMENT_UPLOAD_TTL_MS
            val stored = runCatching {
                BlobStore.finalizeUpload(attachmentId, tempFile)
                encryptedAttachmentRepo.createReplacingPending(
                    id = attachmentId,
                    chatId = chatId,
                    uploaderId = userId,
                    pendingMessageId = pendingMessageId,
                    sha256 = received.sha256,
                    cipherSize = received.byteCount,
                    expiresAt = expiresAt,
                    maxUserBytes = maxAttachmentUserBytes
                )
            }
            if (stored.isFailure) {
                BlobStore.delete(attachmentId)
                when (val error = stored.exceptionOrNull()) {
                    is AttachmentQuotaExceededException -> call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                    is AttachmentMessageAlreadyUsedException -> call.respond(HttpStatusCode.Conflict, ErrorResponse("消息 ID 已被使用"))
                    is AttachmentNotAllowedException -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                    else -> {
                        call.application.log.warn("Encrypted attachment upload failed", error)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件保存失败"))
                    }
                }
                return@post
            }
            stored.getOrThrow().forEach(BlobStore::delete)
            call.respond(
                HttpStatusCode.Created,
                AttachmentUploadResponse(attachmentId, received.sha256, received.byteCount, expiresAt)
            )
        }



        get("/api/attachments/{id}") {
            val userId = call.requireUserId()
            // Bandwidth / bulk-exfil throttle (authenticated participants still rate-limited)
            if (!rateLimiter.acquire("attachment_download:$userId", maxPerMinute = 60)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件下载过于频繁，请稍后再试"))
                return@get
            }
            val attachmentId = parseRawOrEmpty(call.parameters, "id")
            val record = encryptedAttachmentRepo.get(attachmentId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("附件不存在"))
                return@get
            }
            if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                encryptedAttachmentRepo.removeUncommitted(attachmentId, record.uploaderId)
                BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.Gone, ErrorResponse("附件已过期"))
                return@get
            }
            if (record.status != AttachmentStatus.COMMITTED.dbValue) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("附件尚未关联消息"))
                return@get
            }
            if (!encryptedAttachmentRepo.isBoundToLiveMessage(attachmentId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("附件关联消息不存在"))
                return@get
            }
            if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权下载该附件"))
                return@get
            }
            // 与历史消息一致：双向拉黑语义（8.30 隐私修复）——viewer 拉黑了发送者，
            // 或发送者拉黑了 viewer，都不可下载其附件密文。
            val boundMessageId = record.messageId
            if (!boundMessageId.isNullOrBlank()) {
                val senderId = messagingV2Repository
                    .messageMetadata(boundMessageId)
                    ?.senderUserId
                if (senderId != null && senderId != userId && userRepo.isBlockedEitherWay(userId, senderId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权下载该附件"))
                    return@get
                }
            }
            val file = BlobStore.resolve(attachmentId)
            if (file == null || file.length() != record.cipherSize) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("附件密文不可用"))
                return@get
            }
            call.response.header(ATTACHMENT_HASH_HEADER, record.cipherSha256)
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=encrypted-attachment.bin")
            call.response.header("Accept-Ranges", "bytes")
            val rangeHeader = call.request.header("Range")
            // 9.151：多区段（含逗号）忽略回退全量（RFC 允许）；单区段非法/无法满足 → 416
            val range = rangeHeader?.takeIf { ',' !in it }?.let { parseAttachmentRange(it, file.length()) }
            if (rangeHeader != null && ',' !in rangeHeader && range == null) {
                call.response.header("Content-Range", "bytes */${file.length()}")
                call.respondText("", status = ATTACHMENT_RANGE_NOT_SATISFIABLE)
                return@get
            }
            if (range == null) {
                call.respondFile(file)
            } else {
                val remaining = range.last - range.first + 1
                call.response.header("Content-Range", "bytes ${range.first}-${range.last}/${file.length()}")
                call.response.header(HttpHeaders.ContentLength, remaining)
                call.respondOutputStream(
                    contentType = ContentType.Application.OctetStream,
                    status = HttpStatusCode.PartialContent
                ) {
                    java.io.RandomAccessFile(file, "r").use { input ->
                        input.seek(range.first)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var left = remaining
                        while (left > 0L) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) break
                            write(buffer, 0, read)
                            left -= read
                        }
                    }
                }
            }
        }



                    delete("/api/attachments/{id}") {
                        val userId = call.requireUserId()
                        val attachmentId = parseRawOrEmpty(call.parameters, "id")
                        if (!encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("待确认附件不存在"))
                            return@delete
                        }
                        BlobStore.delete(attachmentId)
                        call.respond(
                        buildJsonObject {
        put("status", "ok")
                        }
                    )
                    }
    }
}

private data class ReceivedEncryptedAttachment(val byteCount: Long, val sha256: String)
// 9.151：支持 bytes=a-b / bytes=a- / bytes=-n 三种单区段形式（RFC 9110）。
// 非法或满足不了的单区段返回 null（→ 416）；多区段（含逗号）由调用方选择忽略回退全量。
private fun parseAttachmentRange(value: String, fileSize: Long): LongRange? {
    if (fileSize <= 0L) return null
    val trimmed = value.trim()
    attachmentRangeStartEndRegex.matchEntire(trimmed)?.let { m ->
        val start = m.groupValues[1].toLongOrNull() ?: return null
        if (start >= fileSize) return null
        val endRaw = m.groupValues[2]
        val end = if (endRaw.isEmpty()) fileSize - 1 else (endRaw.toLongOrNull() ?: return null).coerceAtMost(fileSize - 1)
        return if (start <= end) start..end else null
    }
    attachmentRangeSuffixOnlyRegex.matchEntire(trimmed)?.let { m ->
        val length = m.groupValues[1].toLongOrNull() ?: return null
        if (length <= 0L) return null
        return (fileSize - length).coerceAtLeast(0L)..(fileSize - 1)
    }
    return null
}
private suspend fun ApplicationCall.receiveEncryptedAttachment(
    target: java.io.File,
    maxBytes: Long
): ReceivedEncryptedAttachment? {
    val digest = sha256ThreadLocal.get().apply { reset() }
    val channel = receiveChannel()
    var total = 0L
    return try {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) {
                    // 9.150：同上——慢速客户端逐字节上传时等待数据，避免空转烧 CPU
                    channel.awaitContent()
                    continue
                }
                total += read
                if (total > maxBytes) return null
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (total < 17L) null else ReceivedEncryptedAttachment(
            byteCount = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        )
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        target.delete()
        throw cancel
    } catch (_: Exception) {
        target.delete()
        null
    }
}
// Range 头解析：`parseAttachmentRange` 每次调用都重新编译两个 pattern，提到文件级复用。
private val attachmentRangeStartEndRegex = Regex("^bytes=(\\d+)-(\\d*)$")
private val attachmentRangeSuffixOnlyRegex = Regex("^bytes=-(\\d+)$")
