package com.maodouchat.domain.conversation

// 截屏/录屏捕获判定：媒体路径名匹配 + 捕获文件新鲜窗口。
// 从 app 侧 ScreenshotDetector / SecureCaptureScrubber 纯搬移；
// 检测器本身（MediaStore 监听）与删除执行仍在 app 侧，这里只留判定。
object CapturePathPolicy {
    const val IMAGE_MAX_AGE_SEC = 8L
    const val VIDEO_MAX_AGE_SEC = 12L

    // 密聊始终启动检测器（FLAG_SECURE 绕过时的补位）；SCREENSHOT_DETECT 只闸非密聊阅后即焚。
    fun shouldStart(secretActive: Boolean, screenshotDetectFlag: Boolean): Boolean =
        secretActive || screenshotDetectFlag

    fun isScreenshotPath(path: String, name: String = ""): Boolean {
        val p = path.lowercase()
        val n = name.lowercase()
        return p.contains("screenshot") ||
            p.contains("screen_shot") ||
            p.contains("screen-shot") ||
            p.contains("截屏") ||
            p.contains("截图") ||
            n.startsWith("screenshot") ||
            n.contains("screenshot") ||
            n.startsWith("screen_") ||
            n.contains("截屏") ||
            n.contains("截图")
    }

    fun isScreenRecordPath(path: String, name: String = ""): Boolean {
        val p = path.lowercase()
        val n = name.lowercase()
        return p.contains("screenrecord") ||
            p.contains("screen_record") ||
            p.contains("screen-record") ||
            p.contains("screen recordings") ||
            p.contains("screenrecording") ||
            p.contains("录屏") ||
            n.contains("screenrecord") ||
            n.contains("screen_record") ||
            n.contains("record") && (n.contains("screen") || n.contains("scr")) ||
            n.contains("录屏")
    }

    fun shouldDeleteImage(path: String, name: String, ageSec: Long): Boolean =
        ageSec in 0..IMAGE_MAX_AGE_SEC && isScreenshotPath(path, name)

    fun shouldDeleteVideo(path: String, name: String, ageSec: Long): Boolean =
        ageSec in 0..VIDEO_MAX_AGE_SEC && isScreenRecordPath(path, name)
}
