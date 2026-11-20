package com.maodouchat.util

import android.content.Context
import android.net.Uri
import com.maodouchat.core.crypto.EncryptedFile
import com.maodouchat.domain.messaging.EncryptedAttachmentCrypto as DomainEncryptedAttachmentCrypto
import java.io.File

// Context/contentResolver 只能留在 app 侧，加密核心已迁入 :domain:messaging，这里只做委托。
object EncryptedAttachmentCrypto {
    fun encrypt(
        context: Context,
        uri: Uri,
        expectedPlainSize: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): EncryptedFile {
        val directory = File(context.cacheDir, "attachment-uploads").apply { mkdirs() }
        val target = File.createTempFile("encrypted_", ".bin", directory)
        return DomainEncryptedAttachmentCrypto.encryptToTarget(target, expectedPlainSize, { context.contentResolver.openInputStream(uri) }, onProgress)
    }
}
