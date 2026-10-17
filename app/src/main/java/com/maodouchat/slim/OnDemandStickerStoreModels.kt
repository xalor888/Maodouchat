package com.maodouchat.slim

import kotlinx.coroutines.sync.Mutex

/** 贴纸包下载结果（含面向 UI 的状态文案，字符串资源见 values/values-en）。 */
data class StickerPackDownloadResult(
    val packId: String,
    val downloaded: Int,
    val failed: Int,
    val message: String?,
)

internal data class RemoteSticker(val name: String, val url: String, val sha256: String)

internal data class RemotePack(val id: String, val stickers: List<RemoteSticker>)

/** 8.34：每包下载互斥——getSticker 与 UI 手动下载同一包并发时此前互相覆盖 .part 导致 SHA 失败。 */
internal class PackLock(val mutex: Mutex = Mutex(), var users: Int = 0)
