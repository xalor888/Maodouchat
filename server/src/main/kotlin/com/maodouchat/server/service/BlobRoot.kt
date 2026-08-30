package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.io.File

/**
 * B07：共享 blob 存储根与路径安全解析。头像/群头像/动态图片/加密附件共用同一
 * storageRoot 与 canonicalFile + parent 校验，杜绝路径穿越。
 */
internal object BlobRoot {
    val storageRoot: File = File(ServerConfig.storageDir).canonicalFile

    /** 在 [parent] 下按 [name] 解析文件；路径越界返回 null。 */
    fun resolve(parent: File, name: String): File? = runCatching {
        File(parent, name).canonicalFile.takeIf { it.parentFile == parent }
    }.getOrNull()
}
