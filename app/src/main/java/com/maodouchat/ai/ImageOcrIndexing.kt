package com.maodouchat.ai

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.launch

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
