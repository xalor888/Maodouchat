package com.maodouchat.attachment

import java.util.concurrent.atomic.AtomicBoolean

/** Owns preparation resources until an AttachmentTransferEntity is durably handed off. */
internal class AttachmentPreparationLease(
    private val originalSourceUri: String,
    private val deleteEncryptedFile: (String) -> Unit,
    private val deletePreparedSource: (String) -> Unit,
    private val releasePersistablePermission: (String) -> Unit
) {
    private val handedOff = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)

    @Volatile private var encryptedPath: String? = null
    @Volatile private var preparedSourceUri: String? = null

    fun recordEncryptedPath(path: String) {
        encryptedPath = path.takeIf(String::isNotBlank)
    }

    fun recordPreparedSource(uri: String) {
        preparedSourceUri = uri.takeIf(String::isNotBlank)
    }

    fun handOff() {
        handedOff.set(true)
    }

    fun isHandedOff(): Boolean = handedOff.get()

    fun cleanupIfOwned(): Boolean {
        if (handedOff.get() || !cleaned.compareAndSet(false, true)) return false
        encryptedPath?.let(deleteEncryptedFile)
        preparedSourceUri?.let(deletePreparedSource)
        if (originalSourceUri.isNotBlank()) releasePersistablePermission(originalSourceUri)
        return true
    }
}
