package com.maodouchat.server.common

object AttachmentConstants {
    const val ATTACHMENT_UPLOAD_TTL_MS = 24L * 60L * 60L * 1_000L
    const val MEDIA_ORPHAN_GRACE_MS = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_ATTACHMENT_CIPHER_BYTES = 100L * 1024L * 1024L + 64L
}
