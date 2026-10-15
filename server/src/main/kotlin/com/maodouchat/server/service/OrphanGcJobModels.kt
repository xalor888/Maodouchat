package com.maodouchat.server.service

data class OrphanGcResult(
    val deletedAttachments: Int,
    val staleAttachmentFiles: Int,
    val stalePostImages: Int,
    val staleGroupAvatars: Int,
)
