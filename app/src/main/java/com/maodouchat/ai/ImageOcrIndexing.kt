package com.maodouchat.ai

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.launch

/**
 * 端侧 OCR 自动索引的触发入口（U02 延伸：自 `ui/screen/settings/SettingsAiPrivacy`
 * 的 app 单例直连收口）。
 *
 * 语义逐字对齐原实现（两处「开启 AI 默认能力」按钮）：后台 scope 里 fire-and-forget，
 * `runCatching` 吞掉失败（索引失败不打扰用户；同样的动作在别处仍可重试）。
 * 放在非 ui 层后，设置页只调本对象，ui 直连持久层棘轮对应命中归零。
 */
object ImageOcrIndexing {

    /** 立即触发一次 OCR 自动索引扫描（后台执行）。 */
    fun runOnceAsync() {
        MaodouchatApp.instance.applicationScope.launch {
            runCatching {
                MaodouchatApp.instance.imageOcrAutoIndexer.runOnce()
            }
        }
    }
}
