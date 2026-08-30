package com.maodouchat.server.service

import java.io.File

/**
 * B07：可替换 blob 存储后端契约。头像/群头像/动态图片/加密附件共用此接口读写删，
 * 本地实现见 [LocalBlobStorage]；后续可替换为对象存储实现而不改上层。
 */
interface BlobStorage {
    fun resolve(namespace: String, key: String): File?
    fun delete(namespace: String, key: String): Boolean
    fun deleteStale(namespace: String, validKeys: Set<String>, olderThan: Long, acceptKey: (String) -> Boolean): Int
}
