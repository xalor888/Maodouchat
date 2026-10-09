package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自动图片 OCR：识别图片里的文字，写入搜索索引（与手动「提取文字」共用同一服务端能力）。
 *
 * 触发点：
 * - App 启动后（登录态）静默扫描最新图片；
 * - 设置页开启 OCR 开关后立即跑一轮。
 *
 * 运行条件（任一不满足即整体跳过/中止）：
 * - 已登录；
 * - RuntimeFlags.AI_IMAGE_OCR 与 AI_MASTER 开启；
 * - [AiPrivacyPreferences.mayUploadCloudContext]（同意过 AI 处理；未登录 / 已撤销 fail-closed）；
 * - [ImageOcrPreferences.isEnabled]（本机开关，默认开）。
 *
 * 其余约束：
 * - 仅处理 IMAGE 类型消息；GIF/VIDEO 帧不处理（与手动入口一致，UI 仅对 IMAGE 显示）；
 * - 密聊（secret chat）图片一律跳过：结果不应落到可搜索的本地索引；
 * - 已有 ocr 结果（parsedMeta().aiImageAnalyses["ocr"]）的消息跳过；
 * - 走用户本机模型，结果只写本机消息 meta；
 * - 单个运行轮次上限 OCR_IMAGES_PER_RUN 张。
 */
class ImageOcrAutoIndexer(
    private val context: Context,
    private val database: AppDatabase
) {
    private val mutex = Mutex()

    /** 执行一轮自动 OCR；返回成功识别并写入索引的图片数量。 */
    suspend fun runOnce(): Int {
        if (!ImageOcrGates.preconditionsMet(context)) return 0
        val tokenManager = TokenManager.getInstance(context)
        val token = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return 0
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return 0
        // 入口过门禁——与其它后台 worker 一致
        if (!ImageOcrGates.sessionGate(ownerUserId)) return 0
        return mutex.withLock { ImageOcrScan.scanOnce(context, database, token, ownerUserId) }
    }

    internal companion object {
        const val OCR_MODE = "ocr"
        const val OCR_RESULT_MAX_CHARS = 6_000
        const val OCR_IMAGES_PER_RUN = 10
        const val OCR_SCAN_WINDOW = 200
    }
}
