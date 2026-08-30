package com.maodouchat.server.service

import com.maodouchat.server.plugins.ATTACHMENT_UPLOAD_TTL_MS
import com.maodouchat.server.plugins.MEDIA_ORPHAN_GRACE_MS
import com.maodouchat.server.repository.EncryptedAttachmentRepository
import com.maodouchat.server.repository.GroupMediaReferenceRepository
import com.maodouchat.server.repository.PostRepository

/**
 * B07：孤儿 blob 与媒体引用回收 job。
 *
 * 幂等：以时间戳 cutoff 为界，重复执行安全。加密附件先删 DB 过期行再回收磁盘
 * 孤儿文件；媒体侧回收无引用动态图片与无引用群头像。
 */
class OrphanGcJob(
    private val attachmentRepository: EncryptedAttachmentRepository,
    private val postRepository: PostRepository,
    private val groupMediaReferenceRepository: GroupMediaReferenceRepository,
) {
    data class Result(
        val deletedAttachments: Int,
        val staleAttachmentFiles: Int,
        val stalePostImages: Int,
        val staleGroupAvatars: Int,
    )

    fun run(now: Long = System.currentTimeMillis()): Result {
        val deletedAttachments = attachmentRepository.deleteExpired(now)
        deletedAttachments.forEach(BlobStore::delete)
        val staleAttachmentFiles = BlobStore.deleteStaleFiles(
            validIds = attachmentRepository.allIds(),
            olderThan = now - ATTACHMENT_UPLOAD_TTL_MS,
        )
        val olderThan = now - MEDIA_ORPHAN_GRACE_MS
        val stalePostImages = postRepository.deleteStaleUnreferencedImages(olderThan)
        val staleGroupAvatars = FileStorageService.deleteStaleGroupAvatars(
            validFilenames = groupMediaReferenceRepository.allReferencedAvatarFilenames(),
            olderThan = olderThan,
        )
        return Result(deletedAttachments.size, staleAttachmentFiles, stalePostImages, staleGroupAvatars)
    }
}
