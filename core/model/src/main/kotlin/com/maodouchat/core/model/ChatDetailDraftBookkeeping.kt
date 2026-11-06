package com.maodouchat.core.model

/** 草稿记账：保存任务与代际计数。从 ChatDetailDeps 抽出，纯搬移。 */
class ChatDetailDraftBookkeeping {
    var draftSaveJob: kotlinx.coroutines.Job? = null
    /** Bumped on every schedule/clear so a late persist cannot resurrect a cleared draft. */
    var draftGeneration = 0L
}
