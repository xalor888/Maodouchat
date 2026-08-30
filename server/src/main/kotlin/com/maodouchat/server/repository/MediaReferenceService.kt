package com.maodouchat.server.repository

/**
 * B07：媒体引用查询门面。GC 用于判断哪些媒体文件仍在引用中；后续头像/动态图片
 * 引用统一收敛至此（当前覆盖加密附件与群头像，动态图片引用由 PostRepository 自洽）。
 */
class MediaReferenceService(
    private val attachmentRepository: EncryptedAttachmentRepository,
    private val groupMediaReferenceRepository: GroupMediaReferenceRepository,
) {
    fun allReferencedAttachmentIds(): Set<String> = attachmentRepository.allIds()
    fun allReferencedGroupAvatarFilenames(): Set<String> =
        groupMediaReferenceRepository.allReferencedAvatarFilenames()
}
