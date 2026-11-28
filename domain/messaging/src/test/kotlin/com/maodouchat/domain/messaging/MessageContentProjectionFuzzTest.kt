// MessageContentProjection fuzz (148th block). Oracle reimplements the
// two projection functions from production text. The injected lambdas
// are test doubles: decodeReference answers from a per-case refKind
// (null / REF_A / REF_B, never the body) and attachmentUri prefixes the
// id -- the same shape as the existing MessageContentProjectionTest,
// which pins the branches with fixed lambdas. This block adds the
// random differential over types, bodies and metadata.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private val REF_A148 = EncryptedAttachmentReference(
    attachmentId = "att-1",
    keyBase64 = "a2V5",
    ivBase64 = "aXY=",
    cipherSha256 = "cipher-sha",
    plainSha256 = "plain-sha",
    cipherSize = 2048L,
    fileName = "photo.jpg",
    mimeType = "image/jpeg",
    plainSize = 1024L,
    durationMs = 3000L,
)

private val REF_B148 = EncryptedAttachmentReference(
    attachmentId = "att-2",
    keyBase64 = "a2V5Mg==",
    ivBase64 = "aXYy",
    cipherSha256 = "cipher-sha-2",
    plainSha256 = "plain-sha-2",
    cipherSize = 512L,
    fileName = "clip.mp4",
    mimeType = "video/mp4",
    plainSize = 4096L,
    durationMs = null,
)

private val ATTACHMENT_TYPES148 = setOf(
    MessageType.IMAGE,
    MessageType.GIF,
    MessageType.VIDEO,
    MessageType.VOICE,
    MessageType.FILE,
)

private fun refFor148(refKind: Int): EncryptedAttachmentReference? = when (refKind) {
    1 -> REF_A148
    2 -> REF_B148
    else -> null
}

private fun uriFor148(id: String): String = "maodou-attachment://$id"

private fun projectContentOracle148(payload: DecodedContentPayload, refKind: Int): String {
    if (payload.type !in ATTACHMENT_TYPES148) return payload.body
    val reference = refFor148(refKind) ?: return payload.body
    return uriFor148(reference.attachmentId)
}

private fun projectMetadataOracle148(payload: DecodedContentPayload, refKind: Int): MessageMeta {
    if (payload.type !in ATTACHMENT_TYPES148) return payload.metadata
    val reference = refFor148(refKind) ?: return payload.metadata
    return payload.metadata.copy(
        fileName = reference.fileName,
        fileMimeType = reference.mimeType,
        fileSizeBytes = reference.plainSize,
        attachmentId = reference.attachmentId,
        attachmentKeyBase64 = reference.keyBase64,
        attachmentIvBase64 = reference.ivBase64,
        attachmentCipherSha256 = reference.cipherSha256,
        attachmentPlainSha256 = reference.plainSha256,
        attachmentCipherSize = reference.cipherSize,
        voiceDurationMs = reference.durationMs,
    )
}

// ---------- generators (mirrored by Oracle148 GEN, seed 148003) ----------

private val BODY_POOL148 = arrayOf(
    "", "hello", "plain-body", "{\"kind\":\"maodouchat-attachment-v1\"}",
    "{\"not\":\"a-reference\"}", "maodou-attachment://att-1", "att-1",
    "x", "  ", "image.jpg", "{\"kind\":\"other\"}", "null",
)

private val META_STR_POOL148 = arrayOf(
    "", "msg-0", "bob", "old.jpg", "image/png", "att-9", "a2V5", "x",
    "hello", "  ", "voice.mp3",
)

private val META_LONG_POOL148 = arrayOf<Long?>(
    null, 0L, 1L, -1L, 1024L, 2048L, Long.MAX_VALUE, Long.MIN_VALUE,
)

private fun randomNullableStr148(rnd: Random, pool: Array<String>): String? =
    if (rnd.nextInt(4) == 0) null else pool[rnd.nextInt(pool.size)]

private fun randomNullableLong148(rnd: Random): Long? =
    META_LONG_POOL148[rnd.nextInt(META_LONG_POOL148.size)]

private fun randomMeta148(rnd: Random): MessageMeta {
    val mentions = when (rnd.nextInt(4)) {
        0 -> emptyList()
        1 -> listOf("bob")
        2 -> listOf("bob", "alice")
        else -> listOf(META_STR_POOL148[rnd.nextInt(META_STR_POOL148.size)])
    }
    val translations = when (rnd.nextInt(4)) {
        0 -> emptyMap()
        1 -> mapOf("en" to "hello")
        2 -> mapOf("en" to "hello", "zh" to "你好")
        else -> mapOf(META_STR_POOL148[rnd.nextInt(META_STR_POOL148.size)] to "v")
    }
    return MessageMeta(
        mentions = mentions,
        replyToId = randomNullableStr148(rnd, META_STR_POOL148),
        forwardedFrom = randomNullableStr148(rnd, META_STR_POOL148),
        voiceTranscript = randomNullableStr148(rnd, META_STR_POOL148),
        voiceDurationMs = randomNullableLong148(rnd),
        translations = translations,
        preferredTranslationLanguage = randomNullableStr148(rnd, META_STR_POOL148),
        preferredImageAnalysisMode = randomNullableStr148(rnd, META_STR_POOL148),
        preferredFileAnalysisMode = randomNullableStr148(rnd, META_STR_POOL148),
        aiFileLastQuestion = randomNullableStr148(rnd, META_STR_POOL148),
        aiAssisted = rnd.nextBoolean(),
        markdown = rnd.nextBoolean(),
        aiAssistantMode = randomNullableStr148(rnd, META_STR_POOL148),
        fileName = randomNullableStr148(rnd, META_STR_POOL148),
        fileMimeType = randomNullableStr148(rnd, META_STR_POOL148),
        fileSizeBytes = randomNullableLong148(rnd),
        attachmentId = randomNullableStr148(rnd, META_STR_POOL148),
        attachmentKeyBase64 = randomNullableStr148(rnd, META_STR_POOL148),
        attachmentIvBase64 = randomNullableStr148(rnd, META_STR_POOL148),
        attachmentCipherSha256 = randomNullableStr148(rnd, META_STR_POOL148),
        attachmentPlainSha256 = randomNullableStr148(rnd, META_STR_POOL148),
        attachmentCipherSize = randomNullableLong148(rnd),
        viewOnce = rnd.nextBoolean(),
        viewOnceOpened = rnd.nextBoolean(),
        silent = rnd.nextBoolean(),
        spoilerMedia = rnd.nextBoolean(),
        spoilerRevealed = rnd.nextBoolean(),
        forceReply = rnd.nextBoolean(),
    )
}

private fun randomPayload148(rnd: Random): DecodedContentPayload {
    val types = MessageType.entries
    val body = if (rnd.nextInt(2) == 0) BODY_POOL148[rnd.nextInt(BODY_POOL148.size)]
        else {
            val sb = StringBuilder()
            repeat(rnd.nextInt(0, 9)) { sb.append(('a'.code + rnd.nextInt(26)).toChar()) }
            sb.toString()
        }
    return DecodedContentPayload(
        type = types[rnd.nextInt(types.size)],
        body = body,
        metadata = randomMeta148(rnd),
    )
}

class MessageContentProjectionFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val meta = MessageMeta(replyToId = "msg-0", mentions = listOf("bob"))
        // Non-attachment type: body and metadata untouched, lambda unused.
        var decoded = false
        assertEquals(
            "hello",
            MessageContentProjection.projectContent(
                DecodedContentPayload(MessageType.TEXT, "hello", meta),
                decodeReference = { decoded = true; REF_A148 },
                attachmentUri = ::uriFor148,
            ),
        )
        assertEquals(false, decoded)
        assertEquals(meta, MessageContentProjection.projectMetadata(DecodedContentPayload(MessageType.TEXT, "hello", meta), decodeReference = { REF_A148 }))
        assertEquals("hello", projectContentOracle148(DecodedContentPayload(MessageType.TEXT, "hello", meta), 1))
        assertEquals(meta, projectMetadataOracle148(DecodedContentPayload(MessageType.TEXT, "hello", meta), 1))
        // Attachment type without a reference: body/metadata as-is.
        val body = "{\"not\":\"a-reference\"}"
        assertEquals(
            body,
            MessageContentProjection.projectContent(
                DecodedContentPayload(MessageType.IMAGE, body),
                decodeReference = { null },
                attachmentUri = ::uriFor148,
            ),
        )
        assertEquals(body, projectContentOracle148(DecodedContentPayload(MessageType.IMAGE, body), 0))
        assertEquals(meta, projectMetadataOracle148(DecodedContentPayload(MessageType.FILE, "plain-body", meta), 0))
        // Attachment type with a reference: uri from the reference id.
        assertEquals(
            "maodou-attachment://att-1",
            MessageContentProjection.projectContent(
                DecodedContentPayload(MessageType.VIDEO, "{\"kind\":\"maodouchat-attachment-v1\"}"),
                decodeReference = { REF_A148 },
                attachmentUri = ::uriFor148,
            ),
        )
        assertEquals(
            "maodou-attachment://att-1",
            projectContentOracle148(DecodedContentPayload(MessageType.VIDEO, "{\"kind\":\"maodouchat-attachment-v1\"}"), 1),
        )
        assertEquals(
            "maodou-attachment://att-2",
            projectContentOracle148(DecodedContentPayload(MessageType.GIF, "b"), 2),
        )
        // Metadata is enriched from the reference, everything else kept.
        val enriched = MessageContentProjection.projectMetadata(
            DecodedContentPayload(MessageType.VOICE, "{\"kind\":\"maodouchat-attachment-v1\"}", meta),
            decodeReference = { REF_A148 },
        )
        assertEquals("photo.jpg", enriched.fileName)
        assertEquals("image/jpeg", enriched.fileMimeType)
        assertEquals(1024L, enriched.fileSizeBytes)
        assertEquals("att-1", enriched.attachmentId)
        assertEquals("a2V5", enriched.attachmentKeyBase64)
        assertEquals("aXY=", enriched.attachmentIvBase64)
        assertEquals("cipher-sha", enriched.attachmentCipherSha256)
        assertEquals("plain-sha", enriched.attachmentPlainSha256)
        assertEquals(2048L, enriched.attachmentCipherSize)
        assertEquals(3000L, enriched.voiceDurationMs)
        assertEquals("msg-0", enriched.replyToId)
        assertEquals(listOf("bob"), enriched.mentions)
        assertEquals(enriched, projectMetadataOracle148(DecodedContentPayload(MessageType.VOICE, "{\"kind\":\"maodouchat-attachment-v1\"}", meta), 1))
        // REF_B has a null duration: the copy writes null over the old value.
        val enrichedB = projectMetadataOracle148(DecodedContentPayload(MessageType.FILE, "b", MessageMeta(voiceDurationMs = 7000L)), 2)
        assertEquals(null, enrichedB.voiceDurationMs)
        assertEquals("clip.mp4", enrichedB.fileName)
        assertEquals(4096L, enrichedB.fileSizeBytes)
        assertEquals(
            enrichedB,
            MessageContentProjection.projectMetadata(
                DecodedContentPayload(MessageType.FILE, "b", MessageMeta(voiceDurationMs = 7000L)),
                decodeReference = { REF_B148 },
            ),
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(148003)
        repeat(1500) {
            val payload = randomPayload148(rnd)
            val refKind = rnd.nextInt(3)
            assertEquals(
                projectContentOracle148(payload, refKind),
                MessageContentProjection.projectContent(payload, decodeReference = { refFor148(refKind) }, attachmentUri = ::uriFor148),
            )
            assertEquals(
                projectMetadataOracle148(payload, refKind),
                MessageContentProjection.projectMetadata(payload, decodeReference = { refFor148(refKind) }),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every type x refKind with a fixed body/meta.
        val meta = MessageMeta(replyToId = "msg-0", fileName = "old.jpg", voiceDurationMs = 7000L)
        for (type in MessageType.entries) {
            for (refKind in 0..2) {
                val payload = DecodedContentPayload(type, "body", meta)
                assertEquals(
                    projectContentOracle148(payload, refKind),
                    MessageContentProjection.projectContent(payload, decodeReference = { refFor148(refKind) }, attachmentUri = ::uriFor148),
                )
                assertEquals(
                    projectMetadataOracle148(payload, refKind),
                    MessageContentProjection.projectMetadata(payload, decodeReference = { refFor148(refKind) }),
                )
            }
        }
        // Every pool body x refKind for an attachment type.
        for (body in BODY_POOL148) {
            for (refKind in 0..2) {
                val payload = DecodedContentPayload(MessageType.IMAGE, body, meta)
                assertEquals(
                    projectContentOracle148(payload, refKind),
                    MessageContentProjection.projectContent(payload, decodeReference = { refFor148(refKind) }, attachmentUri = ::uriFor148),
                )
            }
        }
        // Single-unit bodies.
        val units = (0..0x7F).toList() +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            val payload = DecodedContentPayload(MessageType.FILE, ch, meta)
            assertEquals(
                projectContentOracle148(payload, 1),
                MessageContentProjection.projectContent(payload, decodeReference = { REF_A148 }, attachmentUri = ::uriFor148),
            )
        }
        // Meta field sweep: each touched field null vs value, refKind 1.
        for (fileName in listOf<String?>(null, "", "old.jpg", "new.png")) {
            for (size in listOf<Long?>(null, 0L, 1024L)) {
                val m = MessageMeta(fileName = fileName, fileSizeBytes = size, voiceDurationMs = 5000L)
                val payload = DecodedContentPayload(MessageType.VOICE, "b", m)
                assertEquals(
                    projectMetadataOracle148(payload, 1),
                    MessageContentProjection.projectMetadata(payload, decodeReference = { REF_A148 }),
                )
                assertEquals(
                    projectMetadataOracle148(payload, 2),
                    MessageContentProjection.projectMetadata(payload, decodeReference = { REF_B148 }),
                )
            }
        }
    }
}
