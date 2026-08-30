package com.maodouchat.server.service

import java.io.File

/**
 * B07：本地文件系统 blob 后端。所有解析经 [BlobRoot] 做路径穿越防护。
 */
object LocalBlobStorage : BlobStorage {
    private val storageRoot = BlobRoot.storageRoot

    override fun resolve(namespace: String, key: String): File? {
        val namespaceRoot = BlobRoot.resolve(storageRoot, namespace)?.takeIf { it.isDirectory } ?: return null
        return BlobRoot.resolve(namespaceRoot, key)
    }

    override fun delete(namespace: String, key: String): Boolean {
        val file = resolve(namespace, key) ?: return false
        return !file.exists() || file.delete()
    }

    override fun deleteStale(namespace: String, validKeys: Set<String>, olderThan: Long, acceptKey: (String) -> Boolean): Int {
        val namespaceRoot = BlobRoot.resolve(storageRoot, namespace)?.takeIf { it.isDirectory } ?: return 0
        return namespaceRoot.listFiles().orEmpty().count { file ->
            file.isFile &&
                file.lastModified() <= olderThan &&
                file.name !in validKeys &&
                acceptKey(file.name) &&
                file.delete()
        }
    }
}
