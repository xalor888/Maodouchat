package com.maodouchat.server.plugins

/**
 * 共享的消息/信令验证常量与函数。
 *
 * Sockets.kt 和 Routing.kt 均使用这些定义，避免重复维护。
 */
internal const val MAX_TEXT_CONTENT_LENGTH = 16_384
// 1.2 MB media becomes ~1.6 MB Base64, then grows again inside a Signal ciphertext envelope.
internal const val MAX_MEDIA_CONTENT_LENGTH = 2_750_000
internal const val MAX_HIDDEN_MESSAGE_CONTENT_LENGTH = 512_000
internal const val MAX_STICKER_WIRE_CONTENT_LENGTH = 65_536
internal const val MAX_SIGNALING_PAYLOAD_LENGTH = 32_768
// BCrypt 实现静默截断 72 字节之后的输入（前 72 字节相同的密码互为等价）——上限必须 ≤72 字节
internal const val MAX_PASSWORD_BYTES = 72

/** 密码合法性：≥6 字符且 UTF-8 字节数 ≤72（BCrypt 截断边界）。 */
internal fun isValidPassword(password: String): Boolean {
    if (password.length < 6) return false
    return password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES
}
internal const val MAX_CALL_ID_LENGTH = 100
internal const val MAX_MESH_CALL_MEMBERS = 6
internal const val MAX_POST_CONTENT_LENGTH = 2_000
internal const val MAX_POST_IMAGES = 9
internal const val MAX_POST_IMAGE_URL_LENGTH = 500
internal const val MAX_COMMENT_CONTENT_LENGTH = 800
internal const val MAX_AI_TEXT_LENGTH = 4_000
internal const val MAX_AI_CONTEXT_MESSAGES = 20
internal const val MAX_AI_CONTEXT_MESSAGE_LENGTH = 1_200
internal const val MAX_AI_SUMMARY_MESSAGES = 48
internal const val MAX_AI_SUMMARY_MESSAGE_LENGTH = 1_500
internal const val MAX_AI_GROUP_ASSISTANT_QUERY_LENGTH = 600
internal const val MAX_AI_GROUP_ASSISTANT_MESSAGES = 40
internal const val MAX_AI_GROUP_ASSISTANT_MESSAGE_LENGTH = 1_200
internal const val MAX_AI_GROUP_ASSISTANT_SENDER_LENGTH = 80
internal const val MAX_AI_SEMANTIC_QUERY_LENGTH = 400
internal const val MAX_AI_SEMANTIC_CANDIDATES = 100
internal const val MAX_AI_SEMANTIC_CANDIDATE_LENGTH = 900
internal const val MAX_AI_SEMANTIC_SENDER_LENGTH = 80
internal const val MAX_AI_TARGET_LANGUAGE_LENGTH = 40
/** Client writing-style preference hint (M5-6); keep small and optional. */
internal const val MAX_AI_STYLE_HINT_LENGTH = 320
internal const val MAX_AI_TRANSCRIBE_BASE64_LENGTH = 1_600_000
internal const val MAX_AI_TRANSCRIBE_LANGUAGE_LENGTH = 20
internal const val MAX_AI_SUMMARY_SYNC_TARGETS = 20
internal const val MAX_AI_SUMMARY_SYNC_ENVELOPE_LENGTH = 256_000
internal const val MAX_AI_IMAGE_BASE64_LENGTH = 1_600_000
internal const val MAX_AI_FILE_BASE64_LENGTH = 1_600_000
internal const val MAX_AI_FILE_NAME_LENGTH = 120
internal const val MAX_AI_FILE_QUESTION_LENGTH = 500

internal val ALLOWED_POST_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")
internal val ALLOWED_MESSAGE_TYPES = setOf("TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE", "VIDEO", "FILE", "SK_DIST")
// FAILED is client-local only; the server delivery ladder is SENT → DELIVERED → READ
internal val ALLOWED_STATUSES = setOf("SENT", "DELIVERED", "READ")
internal val ALLOWED_SIGNALING_TYPES = setOf("offer", "answer", "ice-candidate", "hang-up", "busy", "reject")
internal val ALLOWED_AI_REWRITE_MODES = setOf(
    "polish", "shorten", "formal", "gentle", "casual", "professional", "expand", "bullet", "translate", "clarify"
)
internal val ALLOWED_AI_REPLY_TONES = setOf(
      "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging"
  )
internal val ALLOWED_AI_SUMMARY_STYLES = setOf("brief", "detailed", "decisions", "tasks", "timeline", "risks")
internal val ALLOWED_AI_GROUP_ASSISTANT_MODES = setOf("answer", "summary", "decisions", "tasks", "timeline", "risks")
internal val ALLOWED_AI_IMAGE_MODES = setOf("describe", "ocr", "safety")
internal val ALLOWED_AI_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png")
internal val ALLOWED_AI_FILE_MODES = setOf("summarize", "question")
internal val ALLOWED_AI_FILE_MIME_TYPES = setOf(
    "application/pdf",
    "text/plain",
    "text/markdown",
    "text/csv",
    "application/json",
    "application/xml",
    "text/xml"
)
internal val ALLOWED_AI_AUDIO_MIME_TYPES = setOf("audio/mp4", "audio/m4a", "audio/aac", "audio/mpeg", "audio/wav", "audio/webm")
internal val CLIENT_MESSAGE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")
internal val CALL_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")

internal fun isValidMessagePayload(content: String, type: String, id: String?): Boolean {
    val maxLength = when (type) {
        "IMAGE", "GIF", "VOICE", "VIDEO", "FILE" -> MAX_MEDIA_CONTENT_LENGTH
        "STICKER" -> MAX_STICKER_WIRE_CONTENT_LENGTH
        "LOCATION" -> MAX_STICKER_WIRE_CONTENT_LENGTH
        "SK_DIST" -> MAX_HIDDEN_MESSAGE_CONTENT_LENGTH
        else -> MAX_TEXT_CONTENT_LENGTH
    }
    return type in ALLOWED_MESSAGE_TYPES &&
        content.isNotBlank() &&
        content.length <= maxLength &&
        (id == null || CLIENT_MESSAGE_ID_REGEX.matches(id))
}

internal fun isValidSignalPayload(type: String, payload: String): Boolean {
    val payloadRequired = type !in setOf("hang-up", "busy", "reject")
    return type in ALLOWED_SIGNALING_TYPES &&
        (!payloadRequired || payload.isNotBlank()) &&
        payload.length <= MAX_SIGNALING_PAYLOAD_LENGTH
}

internal fun isValidPostPayload(content: String, imageUrls: List<String>, visibility: String): Boolean {
    return (content.isNotBlank() || imageUrls.isNotEmpty()) &&
        content.length <= MAX_POST_CONTENT_LENGTH &&
        imageUrls.size <= MAX_POST_IMAGES &&
        imageUrls.distinct().size == imageUrls.size &&
        imageUrls.all { it.isNotBlank() && it.length <= MAX_POST_IMAGE_URL_LENGTH } &&
        isValidPostVisibility(visibility)
}

internal fun isValidPostVisibility(visibility: String): Boolean = visibility in ALLOWED_POST_VISIBILITIES

internal fun isValidCommentPayload(content: String): Boolean {
    return content.isNotBlank() && content.length <= MAX_COMMENT_CONTENT_LENGTH
}

/** Non-blank session id required so hangup/clear never wipe unrelated 1:1 signaling. */
internal fun isValidCallId(callId: String): Boolean =
    callId.isNotBlank() && callId.length <= MAX_CALL_ID_LENGTH && CALL_ID_REGEX.matches(callId)

internal fun isValidGroupSignalMetadata(
    groupId: String,
    groupMemberIds: List<String>,
    groupInvite: Boolean,
    callId: String,
    fromUserId: String,
    toUserId: String,
    chatRepo: com.maodouchat.server.repository.ChatRepository
): Boolean {
    if (groupId.isBlank()) return groupMemberIds.isEmpty() && !groupInvite
    if (callId.isBlank()) return false
    if (!CALL_ID_REGEX.matches(groupId) || groupMemberIds.size !in 2..MAX_MESH_CALL_MEMBERS) return false
    val distinctMembers = groupMemberIds.distinct()
    if (distinctMembers.size != groupMemberIds.size || fromUserId !in distinctMembers || toUserId !in distinctMembers) return false
    val chat = chatRepo.getChatById(groupId) ?: return false
    if (!chat.isGroup) return false
    val actualMembers = chat.participants.map { it.id }.toSet()
    return distinctMembers.all { it in actualMembers }
}

internal fun isValidAiRewritePayload(
    text: String,
    mode: String,
    targetLanguage: String?,
    styleHint: String? = null
): Boolean {
    return text.isNotBlank() &&
        text.length <= MAX_AI_TEXT_LENGTH &&
        mode in ALLOWED_AI_REWRITE_MODES &&
        (targetLanguage == null || targetLanguage.length <= MAX_AI_TARGET_LANGUAGE_LENGTH) &&
        (styleHint == null || styleHint.length <= MAX_AI_STYLE_HINT_LENGTH)
}

internal fun isValidAiTranslatePayload(text: String, targetLanguage: String): Boolean {
    return text.isNotBlank() &&
        text.length <= MAX_AI_TEXT_LENGTH &&
        targetLanguage.isNotBlank() &&
        targetLanguage.length <= MAX_AI_TARGET_LANGUAGE_LENGTH
}

internal fun isValidAiSuggestPayload(messages: List<com.maodouchat.server.model.AiContextMessage>, tone: String, count: Int): Boolean {
    return messages.isNotEmpty() &&
        messages.size <= MAX_AI_CONTEXT_MESSAGES &&
        messages.all { it.text.isNotBlank() && it.text.length <= MAX_AI_CONTEXT_MESSAGE_LENGTH } &&
        tone in ALLOWED_AI_REPLY_TONES &&
        count in 1..5
}

internal fun isValidAiSummaryPayload(messages: List<com.maodouchat.server.model.AiContextMessage>, style: String): Boolean {
    return messages.isNotEmpty() &&
        messages.size <= MAX_AI_SUMMARY_MESSAGES &&
        messages.all { it.text.isNotBlank() && it.text.length <= MAX_AI_SUMMARY_MESSAGE_LENGTH } &&
        style in ALLOWED_AI_SUMMARY_STYLES
}

internal fun isValidAiSummarySyncUpload(request: com.maodouchat.server.model.AiSummarySyncUploadRequest): Boolean {
    return CLIENT_MESSAGE_ID_REGEX.matches(request.syncId) &&
        request.senderDeviceId in 1..255 &&
        request.targetDeviceIds.isNotEmpty() &&
        request.targetDeviceIds.size <= MAX_AI_SUMMARY_SYNC_TARGETS &&
        request.targetDeviceIds.distinct().size == request.targetDeviceIds.size &&
        request.targetDeviceIds.all { it in 1..255 && it != request.senderDeviceId } &&
        request.envelope.isNotBlank() &&
        request.envelope.length <= MAX_AI_SUMMARY_SYNC_ENVELOPE_LENGTH
}

internal fun isValidAiSummarySyncAck(request: com.maodouchat.server.model.AiSummarySyncAckRequest): Boolean {
    return request.deviceId in 1..255 &&
        request.envelopeIds.isNotEmpty() &&
        request.envelopeIds.size <= 100 &&
        request.envelopeIds.distinct().size == request.envelopeIds.size &&
        request.envelopeIds.all { CLIENT_MESSAGE_ID_REGEX.matches(it) }
}

internal fun isValidAiGroupAssistantPayload(request: com.maodouchat.server.model.AiGroupAssistantRequest): Boolean {
    return request.query.isNotBlank() &&
        request.query.length <= MAX_AI_GROUP_ASSISTANT_QUERY_LENGTH &&
        request.chatId.isNotBlank() &&
        request.chatId.length <= 100 &&
        request.mode in ALLOWED_AI_GROUP_ASSISTANT_MODES &&
        request.messages.isNotEmpty() &&
        request.messages.size <= MAX_AI_GROUP_ASSISTANT_MESSAGES &&
        request.messages.all { message ->
            message.sender.length <= MAX_AI_GROUP_ASSISTANT_SENDER_LENGTH &&
                message.text.isNotBlank() &&
                message.text.length <= MAX_AI_GROUP_ASSISTANT_MESSAGE_LENGTH
        }
}

internal fun isValidAiSemanticSearchPayload(request: com.maodouchat.server.model.AiSemanticSearchRequest): Boolean {
    return request.query.isNotBlank() &&
        request.query.length <= MAX_AI_SEMANTIC_QUERY_LENGTH &&
        request.chatId.isNotBlank() &&
        request.chatId.length <= 100 &&
        request.limit in 1..20 &&
        request.candidates.isNotEmpty() &&
        request.candidates.size <= MAX_AI_SEMANTIC_CANDIDATES &&
        request.candidates.map { it.messageId }.distinct().size == request.candidates.size &&
        request.candidates.all { candidate ->
            CLIENT_MESSAGE_ID_REGEX.matches(candidate.messageId) &&
                candidate.sender.length <= MAX_AI_SEMANTIC_SENDER_LENGTH &&
                candidate.text.isNotBlank() &&
                candidate.text.length <= MAX_AI_SEMANTIC_CANDIDATE_LENGTH &&
                candidate.timestamp > 0
        }
}

internal fun isValidAiGlobalSemanticSearchPayload(request: com.maodouchat.server.model.AiGlobalSemanticSearchRequest): Boolean {
    val chatIds = request.candidates.map { it.chatId }.distinct()
    return request.query.isNotBlank() &&
        request.query.length <= MAX_AI_SEMANTIC_QUERY_LENGTH &&
        request.limit in 1..30 &&
        request.candidates.isNotEmpty() &&
        request.candidates.size <= MAX_AI_SEMANTIC_CANDIDATES &&
        chatIds.size <= 20 &&
        request.candidates.map { it.chatId to it.messageId }.distinct().size == request.candidates.size &&
        request.candidates.all { candidate ->
            candidate.chatId.isNotBlank() &&
                candidate.chatId.length <= 100 &&
                CLIENT_MESSAGE_ID_REGEX.matches(candidate.messageId) &&
                candidate.sender.length <= MAX_AI_SEMANTIC_SENDER_LENGTH &&
                candidate.text.isNotBlank() &&
                candidate.text.length <= MAX_AI_SEMANTIC_CANDIDATE_LENGTH &&
                candidate.timestamp > 0
        }
}

internal fun isValidAiTranscribePayload(audioBase64: String, mimeType: String, language: String?): Boolean {
    return audioBase64.isNotBlank() &&
        audioBase64.length <= MAX_AI_TRANSCRIBE_BASE64_LENGTH &&
        audioBase64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' } &&
        mimeType.lowercase() in ALLOWED_AI_AUDIO_MIME_TYPES &&
        (language == null || language.length <= MAX_AI_TRANSCRIBE_LANGUAGE_LENGTH)
}

internal fun isValidAiImageAnalyzePayload(request: com.maodouchat.server.model.AiImageAnalyzeRequest): Boolean {
    return request.chatId.isNotBlank() && request.chatId.length <= 100 &&
        request.mode in ALLOWED_AI_IMAGE_MODES &&
        request.mimeType.lowercase() in ALLOWED_AI_IMAGE_MIME_TYPES &&
        request.imageBase64.isNotBlank() &&
        request.imageBase64.length <= MAX_AI_IMAGE_BASE64_LENGTH &&
        request.imageBase64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
}

internal fun isValidAiFileAnalyzePayload(request: com.maodouchat.server.model.AiFileAnalyzeRequest): Boolean {
    val question = request.question?.trim()
    return request.chatId.isNotBlank() && request.chatId.length <= 100 &&
        request.fileName.isNotBlank() && request.fileName.length <= MAX_AI_FILE_NAME_LENGTH &&
        request.fileName.none { it.isISOControl() || it in "\\/:*?\"<>|" } &&
        request.mimeType.lowercase() in ALLOWED_AI_FILE_MIME_TYPES &&
        request.mode in ALLOWED_AI_FILE_MODES &&
        (request.mode != "question" || !question.isNullOrBlank()) &&
        (question == null || question.length <= MAX_AI_FILE_QUESTION_LENGTH) &&
        request.fileBase64.isNotBlank() &&
        request.fileBase64.length <= MAX_AI_FILE_BASE64_LENGTH &&
        request.fileBase64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
}
