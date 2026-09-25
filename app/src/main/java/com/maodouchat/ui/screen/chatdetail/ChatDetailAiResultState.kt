package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maodouchat.ai.AiConversationProfile
import com.maodouchat.ai.AiWeeklyReport
import com.maodouchat.data.repository.AiProfileRepository

/**
 * G335：**本地 AI 聚合结果的「值 / 加载中 / 失败」三态**——会话画像、本周周报、消息分类。
 *
 * 为什么归成一族：这三块是同一个形状的重复——`<结果>`、`<结果>Loading`、`<结果>Failed`，
 * 各自被读取、复位、置错；散在 Route 里共 10 个变量、占 15 行版面，读的人要在几十个状态里
 * 逐个配对「哪个 loading 对应哪个结果」。收进来以后，三态的关系在类型里就能看出来，
 * 也能顺手给出 [reset]：三态必须一起复位，否则会出现「加载中永远转圈」或「失败态挂着旧结果」。
 *
 * 与前面的持有类一致：**不该跨进程存活**（进程重建后重新算就是了，恢复一个半截的 AI 结果反而误导）。
 * `showConversationProfile` / `showWeeklyReport` / `showMessageClassify` 三个「面板开没开」
 * 仍是 Route 里的 `rememberSaveable`——那是 UI 位置，跨旋转该记住，与结果三态不是一回事。
 */
internal class ChatDetailAiResultState {
    /** 会话画像（B4 本地聚合）。 */
    var conversationProfile by mutableStateOf<AiConversationProfile.ConversationProfile?>(null)

    var conversationProfileLoading by mutableStateOf(false)

    var conversationProfileFailed by mutableStateOf(false)

    /** 本周周报。 */
    var weeklyReport by mutableStateOf<AiWeeklyReport.WeeklyReport?>(null)

    var weeklyReportLoading by mutableStateOf(false)

    var weeklyReportFailed by mutableStateOf(false)

    /** 情感回复是否已经请求过（避免重复请求）。 */
    var emotionReplyRequested by mutableStateOf(false)

    /** 消息分类统计（纯本地词典）。 */
    var chatClassifications by mutableStateOf<List<AiProfileRepository.CategoryCount>>(emptyList())

    var classifyLoading by mutableStateOf(false)

    var classifyFailed by mutableStateOf(false)

    /** 复位某一块的三态（重新计算前调用；三态必须一起清）。 */
    fun resetConversationProfile() {
        conversationProfile = null
        conversationProfileLoading = false
        conversationProfileFailed = false
    }

    fun resetWeeklyReport() {
        weeklyReport = null
        weeklyReportLoading = false
        weeklyReportFailed = false
    }

    fun resetClassifications() {
        chatClassifications = emptyList()
        classifyLoading = false
        classifyFailed = false
    }
}
