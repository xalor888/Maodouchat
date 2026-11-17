package com.maodouchat.domain.messaging

// 本机模型文件/视觉任务的提示文案，自 app 侧 LocalAiFileTasks 收敛；
// 文件解析与模型调用仍留在 app 侧。
object AgentFileTaskMessagePolicy {

    fun unsupportedFileType(): String = "不支持该文件类型"

    fun visionUnsupported(model: String): String =
        "当前模型 ($model) 不支持视觉多模态输入，请在「AI 与隐私」设置中开启多模态支持或配置视觉模型（如 GPT-4o / Claude-3 / Qwen-VL 等）"
}
