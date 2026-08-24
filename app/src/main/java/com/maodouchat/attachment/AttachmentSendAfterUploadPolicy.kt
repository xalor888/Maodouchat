package com.maodouchat.attachment

import com.maodouchat.crypto.NoRecipientDevicesException
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import java.io.IOException

/**
 * Upload can succeed while the following POST/encrypt still fails.
 * Keep READY + SENDING for recoverable cases; only treat a 409 as delivered
 * when the server already stored this client message id.
 */
object AttachmentSendAfterUploadPolicy {

    fun isAttachmentNotReadyConflict(error: Throwable?): Boolean {
        val err = error as? ApiException ?: return false
        if (err.kind != ApiFailureKind.HTTP || err.statusCode != 409) return false
        val msg = err.serverMessage.orEmpty()
        return "附件尚未" in msg ||
            "尚未上传完成" in msg ||
            (
                msg.contains("attachment", ignoreCase = true) &&
                    (
                        msg.contains("not ready", ignoreCase = true) ||
                            msg.contains("upload", ignoreCase = true)
                    )
            )
    }

    fun isAlreadyAcceptedDuplicate(error: Throwable?): Boolean {
        val err = error as? ApiException ?: return false
        if (err.kind != ApiFailureKind.HTTP) return false
        if (isAttachmentNotReadyConflict(err)) return false
        val msg = err.serverMessage.orEmpty()
        return err.statusCode == 409 || msg.contains("消息 ID")
    }

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is ApiException ->
            isAttachmentNotReadyConflict(error) ||
                error.kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT) ||
                error.statusCode == 429 ||
                (error.statusCode ?: 0) >= 500
        is IOException -> true
        else -> isTransientCryptoError(error)
    }

    fun isTransientCryptoError(error: Throwable): Boolean {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 6) {
            if (t is NoRecipientDevicesException) return true
            val simpleName = t.javaClass.simpleName
            if (simpleName in setOf(
                    "NoSessionException",
                    "UntrustedIdentityException",
                    "InvalidKeyIdException",
                    "InvalidKeyException",
                    "StaleKeyException",
                    "NoRecipientDevicesException",
                )
            ) return true
            val msg = t.message.orEmpty()
            if (
                "not ready" in msg ||
                "protocol initialization" in msg ||
                "signal_initialization_failed" in msg ||
                "NoRecipientDevices" in simpleName
            ) return true
            t = t.cause
            depth++
        }
        return false
    }
}
