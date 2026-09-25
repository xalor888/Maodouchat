package com.maodouchat.data.repository

import com.maodouchat.network.ApiService

/**
 * **直取**媒体文件（G328c）：把远端图片落到本地文件。
 *
 * 与 `AttachmentTransferCoordinator` 分得很清：那个是聊天附件的正规通道
 * （本地队列、断点续传、失败重试、与消息状态联动）；本类是「点一下保存到相册」
 * 这种一次性下载，失败就是不成功，没有队列也没有重试。
 * 别把附件下载改走这里——会丢掉重试与状态联动。
 *
 * 刻意很薄（不缓存、不重试）：目标路径由调用方给（通常是 cacheDir），
 * 本类不决定文件放哪。
 */
internal class MediaDownloadNetworkRepository(
    private val downloadPostImageApi: suspend (String, String, java.io.File) -> Result<Unit> =
        { token, imageUrl, target -> ApiService.downloadPostImage(token, imageUrl, target) },
) {
    suspend fun downloadPostImage(token: String, imageUrl: String, target: java.io.File): Result<Unit> =
        downloadPostImageApi(token, imageUrl, target)
}
