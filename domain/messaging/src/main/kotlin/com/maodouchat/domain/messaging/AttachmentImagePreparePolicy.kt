package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocalFileMetadata

// 附件图片准备元数据派生：图片压缩后以 .jpg 落盘，元数据文件名取原名去后缀、mime 固定为 image/jpeg。
object AttachmentImagePreparePolicy {
    const val COMPRESSED_IMAGE_MIME_TYPE = "image/jpeg"
    const val MAX_IMAGE_FILE_NAME_STEM_LENGTH = 116

    fun compressedImageMetadata(fileName: String, fileLength: Long): LocalFileMetadata {
        val stem = fileName.substringBeforeLast('.', fileName)
            .ifBlank { "image" }
            .take(MAX_IMAGE_FILE_NAME_STEM_LENGTH)
        return LocalFileMetadata(
            fileName = "$stem.jpg",
            mimeType = COMPRESSED_IMAGE_MIME_TYPE,
            sizeBytes = fileLength,
        )
    }
}
