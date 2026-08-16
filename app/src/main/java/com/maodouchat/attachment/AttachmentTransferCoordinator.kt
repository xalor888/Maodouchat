package com.maodouchat.attachment

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.canRetryWithoutUpload
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.util.MediaCache
import java.io.File
import kotlinx.coroutines.CancellationException

object AttachmentTransferCoordinator {
    suspend fun enqueue(context: Context, transfer: AttachmentTransferEntity) {
        val app = context.applicationContext as MaodouchatApp
        val ownerUserId = currentOwner(context)
        require(ownerUserId.isNotBlank() && transfer.ownerUserId == ownerUserId) { "attachment_transfer_owner_invalid" }
        require(isPrivateUploadFile(context, File(transfer.encryptedPath))) { "attachment_transfer_path_invalid" }
        require(isCurrentOwner(context, ownerUserId)) { "attachment_transfer_owner_changed" }
        app.database.attachmentTransferDao().upsert(transfer)
        if (!isCurrentOwner(context, ownerUserId)) return
        AttachmentTransferScheduler.schedule(context, transfer.messageId, ownerUserId, replace = true)
    }

    suspend fun pause(context: Context, messageId: String): Boolean {
        val app = context.applicationContext as MaodouchatApp
        val ownerUserId = currentOwner(context)
        if (ownerUserId.isBlank()) return false
        if (!isCurrentOwner(context, ownerUserId)) return false
        val updated = app.database.attachmentTransferDao().pause(messageId, ownerUserId = ownerUserId) == 1
        if (!isCurrentOwner(context, ownerUserId)) return false
        if (updated) AttachmentTransferScheduler.cancel(context, messageId, ownerUserId)
        return updated
    }

    suspend fun resume(context: Context, messageId: String): Boolean =
        resume(context, messageId, currentOwner(context))

    suspend fun resume(context: Context, messageId: String, expectedOwnerUserId: String): Boolean {
        val app = context.applicationContext as MaodouchatApp
        val dao = app.database.attachmentTransferDao()
        val ownerUserId = currentOwner(context)
        if (ownerUserId.isBlank() || ownerUserId != expectedOwnerUserId) return false
        val transfer = dao.get(messageId, ownerUserId = ownerUserId) ?: return false
        if (!isCurrentOwner(context, ownerUserId)) return false
        if (transfer.canRetryWithoutUpload()) {
            val ready = dao.retryCompletedUpload(messageId, ownerUserId = ownerUserId) == 1
            if (!isCurrentOwner(context, ownerUserId)) return false
            if (ready) AttachmentTransferScheduler.schedule(context, messageId, ownerUserId, replace = true)
            return ready
        }
        if (!isPrivateUploadFile(context, File(transfer.encryptedPath)) || !File(transfer.encryptedPath).isFile) {
            if (!isCurrentOwner(context, ownerUserId)) return false
            dao.markFailed(messageId, "SOURCE_MISSING", ownerUserId = ownerUserId)
            return false
        }
        if (dao.resume(messageId, ownerUserId = ownerUserId) != 1) return false
        if (!isCurrentOwner(context, ownerUserId)) return false
        AttachmentTransferScheduler.schedule(context, messageId, ownerUserId, replace = true)
        return true
    }

    suspend fun cancel(context: Context, messageId: String, deleteServerObject: Boolean = true): Boolean =
        cancel(context, messageId, currentOwner(context), deleteServerObject)

    suspend fun cancel(
        context: Context,
        messageId: String,
        expectedOwnerUserId: String,
        deleteServerObject: Boolean = true
    ): Boolean {
        val app = context.applicationContext as MaodouchatApp
        val dao = app.database.attachmentTransferDao()
        val ownerUserId = currentOwner(context)
        if (ownerUserId.isBlank() || ownerUserId != expectedOwnerUserId) return false
        val transfer = dao.get(messageId, ownerUserId = ownerUserId) ?: return false
        if (!isCurrentOwner(context, ownerUserId)) return false
        // BUG 1.1 fix: 不取消正在 SENDING/FINALIZING 的传输，防止中断 finalize 导致消息卡在 SENDING
        if (transfer.state == "SENDING") {
            android.util.Log.w("AttachmentTransferCoordinator", "Skip cancel: transfer $messageId is SENDING")
            return false
        }
        AttachmentTransferScheduler.cancel(context, messageId, ownerUserId)
        // finalize 可能在本函数读到 READY 后立刻 claim 成 SENDING。先做带状态条件的删除，
        // 只有确认仍未被 claim 时才清理服务端对象与本地文件；否则留给 finalizer 收尾。
        if (dao.deleteUnlessSending(messageId, ownerUserId = ownerUserId) != 1) {
            return false
        }
        // 8.49 修复：服务端对象删除降级为 best-effort——失败（会话失效/网络）不再中断本地清理。
        // 服务端未提交对象有 24h TTL 兜底；此前 return false 会把密文/源文件与 DB 行全部留下，
        // 且孤儿 GC 因行存在而保护它们，登出后账号数据残留磁盘。
        if (deleteServerObject) {
            runCatching { deleteServerObject(context, transfer) }
                .onFailure { android.util.Log.w("AttachmentTransferCoordinator", "server object delete failed for $messageId", it) }
        }
        deletePrivateUploadFile(context, transfer.encryptedPath)
        MediaCache.deletePreparedAttachmentSource(context, transfer.sourceUri)
        MediaCache.releasePersistableReadPermission(context, transfer.sourceUri)
        return true
    }

    suspend fun complete(context: Context, messageId: String, ownerUserId: String) {
        val app = context.applicationContext as MaodouchatApp
        val dao = app.database.attachmentTransferDao()
        if (ownerUserId.isBlank() || !isCurrentOwner(context, ownerUserId)) return
        val transfer = dao.get(messageId, ownerUserId = ownerUserId) ?: return
        if (!isCurrentOwner(context, ownerUserId)) return
        AttachmentTransferScheduler.cancel(context, messageId, ownerUserId)
        deletePrivateUploadFile(context, transfer.encryptedPath)
        MediaCache.deletePreparedAttachmentSource(context, transfer.sourceUri)
        MediaCache.releasePersistableReadPermission(context, transfer.sourceUri)
        if (!isCurrentOwner(context, ownerUserId)) return
        dao.delete(messageId, ownerUserId = ownerUserId)
    }

    suspend fun cancelForChat(context: Context, chatId: String) {
        val app = context.applicationContext as MaodouchatApp
        val ownerUserId = currentOwner(context)
        if (ownerUserId.isBlank()) return
        val transfers = app.database.attachmentTransferDao().getByChat(chatId, ownerUserId = ownerUserId)
        if (!isCurrentOwner(context, ownerUserId)) return
        transfers.forEach { transfer ->
            try {
                if (transfer.state == "SENDING") {
                    // SENDING = 上传已完成、finalize（verify→sendMessage）可能在途：
                    // 绝不能在此时删除服务端对象——否则 finalize 成功后消息引用已删附件，
                    // 收件人永久无法下载。只取消调度 + 清本地文件 + 删行；
                    // 服务端未提交对象由 24h TTL 兜底，finalize 完成时 complete() 幂等收尾。
                    AttachmentTransferScheduler.cancel(context, transfer.messageId, ownerUserId)
                    deletePrivateUploadFile(context, transfer.encryptedPath)
                    MediaCache.deletePreparedAttachmentSource(context, transfer.sourceUri)
                    MediaCache.releasePersistableReadPermission(context, transfer.sourceUri)
                    app.database.attachmentTransferDao().delete(transfer.messageId, ownerUserId = ownerUserId)
                } else {
                    cancel(context, transfer.messageId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AttachmentTransferCoordinator", "cancelForChat cleanup failed for ${transfer.messageId}", e)
            }
        }
    }

    suspend fun deleteAll(context: Context) {
        val app = context.applicationContext as MaodouchatApp
        val dao = app.database.attachmentTransferDao()
        val ownerUserId = currentOwner(context)
        if (ownerUserId.isBlank()) return
        val transfers = dao.getAll(ownerUserId = ownerUserId)
        if (!isCurrentOwner(context, ownerUserId)) return
        transfers.forEach { transfer ->
            if (!isCurrentOwner(context, ownerUserId)) return
            AttachmentTransferScheduler.cancel(context, transfer.messageId, ownerUserId)
            if (transfer.state != "SENDING") {
                // SENDING（finalize 在途）不删服务端对象，防止消息引用已删附件（见 cancelForChat）。
                // 8.49 修复：服务端删除失败不再 return 中断剩余条目——best-effort + 24h TTL 兜底
                runCatching { deleteServerObject(context, transfer) }
                    .onFailure { android.util.Log.w("AttachmentTransferCoordinator", "server object delete failed for ${transfer.messageId}", it) }
            }
            deletePrivateUploadFile(context, transfer.encryptedPath)
            MediaCache.deletePreparedAttachmentSource(context, transfer.sourceUri)
            MediaCache.releasePersistableReadPermission(context, transfer.sourceUri)
            if (!isCurrentOwner(context, ownerUserId)) return
            app.database.messageDao().deleteMessageById(transfer.messageId)
            if (!isCurrentOwner(context, ownerUserId)) return
            dao.delete(transfer.messageId, ownerUserId = ownerUserId)
        }
    }

    suspend fun reconcile(context: Context) {
        val app = context.applicationContext as MaodouchatApp
        val dao = app.database.attachmentTransferDao()
        val ownerUserId = currentOwner(context)
        if (ownerUserId.isBlank()) return
        // 只释放陈旧 SENDING：同进程内可能仍有 worker 持有 claim；整表 releaseAll 会双 finalize
        val staleBefore = System.currentTimeMillis() - STALE_SENDING_MS
        dao.releaseStaleSending(staleBeforeMs = staleBefore, ownerUserId = ownerUserId)
        if (!isCurrentOwner(context, ownerUserId)) return
        val transfers = dao.getAll(ownerUserId = ownerUserId)
        if (!isCurrentOwner(context, ownerUserId)) return
        // The cache roots are shared by all retained accounts. Protect every owned path so
        // reconciling one account never deletes another account's dormant attachment files.
        val allAccountTransfers = dao.getAllAccounts()
        if (!isCurrentOwner(context, ownerUserId)) return
        val validPaths = allAccountTransfers.mapNotNull { transfer ->
            File(transfer.encryptedPath).takeIf { isPrivateUploadFile(context, it) }
                ?.let { runCatching { it.canonicalPath }.getOrNull() }
        }.toSet()
        val validSourcePaths = allAccountTransfers.mapNotNull { transfer ->
            MediaCache.preparedAttachmentSourceFile(context, transfer.sourceUri)
                ?.let { runCatching { it.canonicalPath }.getOrNull() }
        }.toSet()
        val uploadRoot = File(context.cacheDir, "attachment-uploads")
        val sourceRoot = File(context.cacheDir, "attachment-sources")
        val now = System.currentTimeMillis()
        if (!isCurrentOwner(context, ownerUserId)) return
        uploadRoot.listFiles()?.filter { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
            file.isFile && canonicalPath != null && canonicalPath !in validPaths && now - file.lastModified() > ORPHAN_GRACE_MS
        }?.forEach { it.delete() }
        sourceRoot.listFiles()?.filter { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
            file.isFile && canonicalPath != null && canonicalPath !in validSourcePaths && now - file.lastModified() > ORPHAN_GRACE_MS
        }?.forEach { it.delete() }
        transfers.forEach { transfer ->
            if (!isCurrentOwner(context, ownerUserId)) return
            val file = File(transfer.encryptedPath)
            if (!transfer.hasCompletedUpload() && (!isPrivateUploadFile(context, file) || !file.isFile || file.length() != transfer.cipherSize)) {
                dao.markFailed(transfer.messageId, "SOURCE_MISSING", ownerUserId = ownerUserId)
            }
        }
        if (!isCurrentOwner(context, ownerUserId)) return
        AttachmentTransferScheduler.reconcile(context, dao, ownerUserId)
    }

    private suspend fun deleteServerObject(context: Context, transfer: AttachmentTransferEntity): Boolean {
        val attachmentId = transfer.attachmentId ?: return true
        val tokenManager = TokenManager.getInstance(context.applicationContext)
        if (tokenManager.getUserId().orEmpty() != transfer.ownerUserId) return false
        val token = tokenManager.getToken().orEmpty()
        if (tokenManager.getUserId().orEmpty() != transfer.ownerUserId || token.isBlank()) return false
        if (token.isNotBlank()) ApiService.deleteUncommittedAttachment(token, attachmentId)
        return isCurrentOwner(context, transfer.ownerUserId)
    }

    private fun isPrivateUploadFile(context: Context, file: File): Boolean = runCatching {
        val root = File(context.cacheDir, "attachment-uploads").canonicalPath + File.separator
        file.canonicalPath.startsWith(root)
    }.getOrDefault(false)

    private fun deletePrivateUploadFile(context: Context, path: String) {
        val file = File(path)
        if (isPrivateUploadFile(context, file)) file.delete()
    }

    private fun currentOwner(context: Context): String =
        TokenManager.getInstance(context.applicationContext).getUserId().orEmpty()

    private fun isCurrentOwner(context: Context, expectedOwnerUserId: String): Boolean =
        currentOwner(context) == expectedOwnerUserId

    private const val ORPHAN_GRACE_MS = 24L * 60L * 60L * 1_000L
    /** SENDING claim older than this is assumed abandoned after process death. */
    private const val STALE_SENDING_MS = 2L * 60L * 1_000L
}
