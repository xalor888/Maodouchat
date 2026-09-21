package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.network.AiContextMessage
import com.maodouchat.util.RuntimeFlags
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 离线建议（G166 从 ChatDetailAiGeneration.kt 抽出，675 行）。
 *
 * `buildOfflineAiSuggestions` 是本文件的主体：按最后一条消息的关键词
 * 命中本地话术库，给出最多 4 条草稿建议。它不访问网络，
 * 所以 AI 服务不可用时用户仍能得到可用回复。
 *
 * 抽出理由：原文件 1975 行且已顶到在监上限；这一块与
 * 「请求 / 流式 / 取消」逻辑无耦合，是最容易安全切分的大块。
 */
internal fun ChatDetailViewModel.generateAiSuggestions(tone: String = "friendly") {
    val contextMessages = buildAiContextMessages(limit = 16)
    if (contextMessages.isEmpty()) return
    val request = captureAiRequestSnapshot()
    if (request == null) {
        _uiState.update { it.copy(aiReplyStreamErrorCode = AiOperationError.CONTEXT_MISSING) }
        return
    }
    aiReplyStreamJob?.cancel()
    val generation = aiReplyGate.next()
    val safeTone = when (tone.trim().lowercase()) {
        "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
        else -> "friendly"
    }
    _uiState.update {
        it.copy(
            isAiWorking = true,
            isAiReplyStreaming = true,
            aiReplyStreamErrorCode = null,
            aiSuggestions = emptyList(),
            groupEncryptionWarning = null
        )
    }
    aiReplyStreamJob = viewModelScope.launch {
        try {
            requireAiRequestCurrent(request)
            com.maodouchat.ai.agent.LocalAiGateway.suggestReplies(
                getApplication(),
                contextMessages,
                safeTone,
                4
            ).fold(
                onSuccess = { replies ->
                    if (!aiReplyGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                    _uiState.update {
                        it.copy(
                            aiSuggestions = replies.map { reply -> reply.take(500) }.filter { it.isNotBlank() }.take(4),
                            isAiReplyStreaming = false,
                            isAiWorking = false,
                            aiReplyStreamErrorCode = if (replies.isEmpty()) AiOperationError.EMPTY_RESULT else null
                        )
                    }
                },
                onFailure = { error ->
                    if (!aiReplyGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                    val offline = buildOfflineAiSuggestions(contextMessages, safeTone)
                    _uiState.update {
                        it.copy(
                            isAiReplyStreaming = false,
                            isAiWorking = false,
                            aiSuggestions = offline.ifEmpty { it.aiSuggestions },
                            aiReplyStreamErrorCode = if (offline.isNotEmpty()) null else aiOperationErrorCode(error),
                            groupEncryptionWarning = if (offline.isNotEmpty()) {
                                text(R.string.ai_offline_suggestions_hint)
                            } else it.groupEncryptionWarning
                        )
                    }
                }
            )
            // Empty upstream result -> offline heuristic fallback
            if (aiReplyGate.isCurrent(generation) &&
                isAiRequestCurrent(request) &&
                _uiState.value.aiSuggestions.isEmpty() &&
                _uiState.value.aiReplyStreamErrorCode == AiOperationError.EMPTY_RESULT
            ) {
                val offline = buildOfflineAiSuggestions(contextMessages, safeTone)
                if (offline.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            aiSuggestions = offline,
                            aiReplyStreamErrorCode = null,
                            groupEncryptionWarning = text(R.string.ai_offline_suggestions_hint)
                        )
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (aiReplyGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                _uiState.update {
                    it.copy(isAiReplyStreaming = false, isAiWorking = false)
                }
            }
            throw error
        }
    }
}

/** Local, privacy-preserving reply chips when cloud AI is unavailable. */
private fun ChatDetailViewModel.buildOfflineAiSuggestions(
    contextMessages: List<com.maodouchat.network.AiContextMessage>,
    tone: String
): List<String> {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) return emptyList()
    val texts = contextMessages.map { it.text.trim() }.filter { it.isNotBlank() }
    if (texts.isEmpty()) return emptyList()
    val seed = texts.last().take(120)
    val recent = texts.takeLast(3).joinToString(" ").take(240)
    val lower = (seed + " " + recent).lowercase()
    val base = when {
        "?" in seed || "？" in seed || lower.startsWith("why") || lower.startsWith("how") ||
            "吗" in seed || "么" in seed || "呢" in seed -> listOf(
            "好问题，我的想法是……",
            "可以，稍等我整理一下再回你。",
            "我更倾向这个方向，你觉得呢？"
        )
        offlineHas(lower, seed, listOf("谢谢", "thanks", "thank")) -> listOf(
            "不客气～",
            "应该的，有需要再叫我。",
            "小事一桩。"
        )
        offlineHas(lower, seed, listOf("你好", "hello", "hi ", "在吗", "在不在")) -> listOf(
            "在的，怎么啦？",
            "嗨，刚看到～",
            "在呢，说吧。"
        )
        offlineHas(lower, seed, listOf("约", "见面", "吃饭", "电影")) -> listOf(
            "时间地点你定，我配合～",
            "可以啊，周末怎么样？",
            "我想去，细节再敲定。"
        )
        offlineHas(lower, seed, listOf("难过", "伤心", "累", "压力")) -> listOf(
            "我在这儿听你说。",
            "辛苦了，先歇一会儿吧。",
            "需要的话我陪你聊聊。"
        )
                    offlineHas(lower, seed, listOf("ok", "okay", "good night", "gn", "晚安", "好的", "行")) -> listOf(
            "好的，收到。",
            "嗯嗯，晚点聊。",
            "Alright, talk soon."
        )
        offlineHas(lower, seed, listOf("sorry", "抱歉", "不好意思", "对不起")) -> listOf(
            "没关系。",
            "理解你，没事的。",
            "It's okay, no worries."
        )
        offlineHas(lower, seed, listOf("love", "喜欢", "爱你", "么么")) -> listOf(
            "我也是～",
            "收到满满的喜欢。",
            "同样的感觉。"
        )
                    offlineHas(lower, seed, listOf("meeting", "会议", "开会", "sync")) -> listOf(
            "好的，我改一下时间。",
            "议程我稍后发你。",
            "Can we do a short call?"
        )
        offlineHas(lower, seed, listOf("price", "多少钱", "费用", "报价")) -> listOf(
            "我整理一版报价给你。",
            "方便说下预算范围吗？",
            "Let me send options."
        )
        offlineHas(lower, seed, listOf("photo", "图片", "照片", "看看")) -> listOf(
            "发我看看～",
            "收到，我仔细看下。",
            "Looks good!"
        )
        offlineHas(lower, seed, listOf("code", "bug", "报错", "崩溃", "error")) -> listOf(
            "日志发我一段。",
            "我这边复现一下。",
            "Might be a race; checking."
        )
        offlineHas(lower, seed, listOf("weather", "天气", "下雨", "温度")) -> listOf(
            "记得看下天气预报再出门～",
            "要不要改成室内活动？",
            "我这边帮你记着关注天气变化。"
        )
        offlineHas(lower, seed, listOf("deadline", "截止", "ddl", "明天交")) -> listOf(
            "截止日期我记下了，要不要拆成待办？",
            "先列三点关键路径，我陪你盯进度。",
            "需要我帮你写个简短提醒吗？"
        )
        offlineHas(lower, seed, listOf("travel", "出差", "高铁", "飞机", "酒店")) -> listOf(
            "行程我可以帮你整理成清单。",
            "要不要同步一下出发/到达时间？",
            "路上注意安全，到了报个平安～"
        )
        offlineHas(lower, seed, listOf("health", "感冒", "生病", "医院", "吃药")) -> listOf(
            "多休息喝温水，严重就去看医生。",
            "需要我帮你请假话术吗？",
            "好好照顾自己，别硬撑。"
        )
        offlineHas(lower, seed, listOf("weekend", "周末", "假期", "vacation", "holiday")) -> listOf(
            "周末有什么安排？",
            "好好休息，周一见。",
            "Any fun plans this weekend?"
        )
        offlineHas(lower, seed, listOf("congrats", "恭喜", "祝贺", "庆祝", "offer")) -> listOf(
            "太棒了，恭喜你！",
            "值得好好庆祝一下。",
            "Congrats — well earned!"
        )
        offlineHas(lower, seed, listOf("traffic", "堵车", "迟到", "晚到", "delay")) -> listOf(
            "注意安全，到了说一声。",
            "没关系，我可以等你。",
            "Safe travels, take your time."
        )
        offlineHas(lower, seed, listOf("food", "吃饭", "外卖", "餐厅", "lunch", "dinner")) -> listOf(
            "要一起点外卖吗？",
            "我可以帮你列几个选项。",
            "I'm hungry too — any preference?"
        )
        offlineHas(lower, seed, listOf("game", "游戏", "开黑", "上分", "match")) -> listOf(
            "现在开一局？",
            "我准备好了，你定模式。",
            "Queue up, I'm in."
        )
        offlineHas(lower, seed, listOf("movie", "电影", "剧", "追剧", "netflix")) -> listOf(
            "有推荐的片单吗？",
            "今晚一起看？",
            "Send the title, I'll check."
        )
        offlineHas(lower, seed, listOf("work", "加班", " ent", "项目", "deadline")) -> listOf(
            "进度我记下了，需要帮忙拆任务吗？",
            "先聚焦最关键的一件事。",
            "Want a quick status checklist?"
        )
        offlineHas(lower, seed, listOf("money", "转账", "付款", "账单", "pay")) -> listOf(
            "金额确认后我再操作。",
            "发我账单明细～",
            "I'll confirm and get back."
        )
        offlineHas(lower, seed, listOf("birthday", "生日快乐", "过生")) -> listOf(
            "生日快乐！今天过得开心点。",
            "要不要一起安排个小庆祝？",
            "送你一个虚拟蛋糕"
        )
        offlineHas(lower, seed, listOf("study", "学习", "考试", "exam", "homework")) -> listOf(
            "要不要一起复盘一下重点？",
            "先休息五分钟再继续。",
            "I can quiz you on the hard parts."
        )
        offlineHas(lower, seed, listOf("sport", "跑步", "健身", "gym", "workout")) -> listOf(
            "今天练哪一块？",
            "加油，注意拉伸。",
            "Send me your PR, I want to cheer."
        )
        offlineHas(lower, seed, listOf("music", "歌", "playlist", "演唱会")) -> listOf(
            "发我歌单听听。",
            "这首循环了好几遍。",
            "Any new recommendations?"
        )
        offlineHas(lower, seed, listOf("pet", "猫", "狗", "铲屎", "puppy", "kitty")) -> listOf(
            "毛孩子今天乖不乖？",
            "求吸猫/吸狗现场。",
            "Pet tax please "
        )
        offlineHas(lower, seed, listOf("secret", "密聊", "加密", "e2ee")) -> listOf(
            "敏感内容我们用密聊说。",
            "记得开阅后即焚。",
            "I'll keep this private."
        )
        offlineHas(lower, seed, listOf("sleep", "失眠", "困", "熬夜", "insomnia")) -> listOf(
            "早点休息，明天再说。",
            "我先不打扰你了。",
            "Sleep well — talk tomorrow."
        )
        offlineHas(lower, seed, listOf("coffee", "咖啡", "奶茶", "tea")) -> listOf(
            "来一杯提神？",
            "我请你喝。",
            "Coffee or tea?"
        )
        offlineHas(lower, seed, listOf("rain", "下雪", "台风", "storm")) -> listOf(
            "出门记得带伞。",
            "注意安全。",
            "Stay dry out there."
        )
        offlineHas(lower, seed, listOf("meeting cancel", "取消", "改期", "reschedule")) -> listOf(
            "那我们另约时间。",
            "收到，我改日历了。",
            "No problem — propose a new slot."
        )
        offlineHas(lower, seed, listOf("flight", "航班", "高铁", "train", "airport")) -> listOf(
            "一路顺风，落地报平安。",
            "需要我帮你看时刻表吗？",
            "Safe travels — ping me when you land."
        )
        offlineHas(lower, seed, listOf("wifi", "网络", "断网", "lag", "卡顿")) -> listOf(
            "可能是网络波动，稍后再试。",
            "我这边也有点卡。",
            "Try switching networks?"
        )
        offlineHas(lower, seed, listOf("gift", "礼物", "惊喜", "present")) -> listOf(
            "要不要一起挑个礼物？",
            "保密，别剧透～",
            "I have an idea — call me."
        )
        offlineHas(lower, seed, listOf("interview", "面试", "offer", "hr")) -> listOf(
            "祝你顺利，稳住发挥。",
            "需要我帮你过一遍常见问题吗？",
            "You've got this — knock them out."
        )
        offlineHas(lower, seed, listOf("battery", "没电", "充电", "low battery")) -> listOf(
            "快没电了，我先去充电。",
            "回头再聊～",
            "Powering up — brb."
        )
        offlineHas(lower, seed, listOf("map", "迷路", "导航", "lost", "directions")) -> listOf(
            "发我定位，我帮你看。",
            "别急，先找个地标。",
            "Share your pin, I'll guide you."
        )
        offlineHas(lower, seed, listOf("package", "快递", "外卖到了", "delivery")) -> listOf(
            "收到了说一声。",
            "我下楼拿。",
            "I'll grab it."
        )
        offlineHas(lower, seed, listOf("//", "code review", "pr ", "merge")) -> listOf(
            "我晚点看你的 PR。",
            "有冲突先 rebase 一下。",
            "LGTM with nits — shipping."
        )
        offlineHas(lower, seed, listOf("cook", "做饭", "菜谱", "recipe")) -> listOf(
            "今晚想吃什么？",
            "发我菜谱链接～",
            "I can help plan the menu."
        )
        offlineHas(lower, seed, listOf("plant", "浇花", "绿植", "garden")) -> listOf(
            "别忘了浇水。",
            "新芽发了吗？",
            "Plant tax photos please."
        )
        offlineHas(lower, seed, listOf("book", "读书", "小说", "reading")) -> listOf(
            "最近在看什么？",
            "读完安利我。",
            "Drop the title — adding to my list."
        )
        offlineHas(lower, seed, listOf("gym fail", "没去练", "偷懒", "rest day")) -> listOf(
            "休息也是训练的一部分。",
            "明天补上就好。",
            "Rest day accepted."
        )
        offlineHas(lower, seed, listOf("password", "密码", "2fa", "验证码", "totp")) -> listOf(
            "别在群里发验证码。",
            "建议开 TOTP 两步验证。",
            "Reset via secure channel only."
        )
        offlineHas(lower, seed, listOf("screenshot", "截图", "录屏", "screen record")) -> listOf(
            "密聊请勿截图。",
            "有盲水印可追溯。",
            "Use view-once if sensitive."
        )
        offlineHas(lower, seed, listOf("budget", "预算", "省钱", "理财")) -> listOf(
            "我们列个简单预算表。",
            "先区分必要与可选开支。",
            "Want a 3-line budget?"
        )
        offlineHas(lower, seed, listOf("doctor", "医院", "挂号", "clinic")) -> listOf(
            "早去排队，记得带证件。",
            "需要我陪你吗？",
            "Feel better soon."
        )
        offlineHas(lower, seed, listOf("parking", "停车", "挪车", "garage")) -> listOf(
            "我马上挪一下。",
            "发我位置。",
            "On my way to move it."
        )
        offlineHas(lower, seed, listOf("vpn", "代理", "翻墙", "proxy")) -> listOf(
            "注意账号安全，别分享节点。",
            "优先用官方通道。",
            "Keep credentials private."
        )
        offlineHas(lower, seed, listOf("backup", "备份", "导出聊天", "export chat")) -> listOf(
            "密聊内容不建议明文导出。",
            "可用加密备份方案。",
            "Prefer encrypted backups only."
        )
        offlineHas(lower, seed, listOf("pin message", "unpin message", "message pin")) -> listOf(
            "Pinned for the group.",
            "Unpinned — no longer sticky.",
            "Pin the important update."
        )
        offlineHas(lower, seed, listOf("revoke message", "delete message", "unsend")) -> listOf(
            "Revoked — pretend you didn't see it.",
            "Deleted on my side.",
            "I'll unsend that."
        )
        offlineHas(lower, seed, listOf("pin the mood", "revoke rush", "secret signal")) -> listOf(
            "Pin the mood for today.",
            "Revoke rush starts now.",
            "Secret signal received."
        )
        offlineHas(lower, seed, listOf("doc hunt", "meaning race", "insight sprint", "ai file", "semantic search", "analyze file")) -> listOf(
            "Doc hunt - find the clause.",
            "Meaning race - semantic win.",
            "Insight sprint - one key takeaway.",
            "AI file analysis is admin-gated.",
            "Semantic search can be limited."
        )
        offlineHas(lower, seed, listOf("pixel quest", "assist circle", "decision dash", "ai analyze", "group assistant", "image analyze")) -> listOf(
            "Pixel quest - find the clue in the photo.",
            "Assist circle - group AI recap.",
            "Decision dash - pick next steps.",
            "AI image analysis is admin-gated.",
            "Group assistant can be limited."
        )
        offlineHas(lower, seed, listOf("suggest circle", "voice race", "reply sprint", "ai suggest", "ai transcribe", "suggest replies")) -> listOf(
            "Suggest circle - share a quick reply idea.",
            "Voice race - short clear note.",
            "Reply sprint - three options fast.",
            "AI suggest replies is admin-gated.",
            "AI transcribe can be limited."
        )
        offlineHas(lower, seed, listOf("photo race", "clip dash", "frame hunt", "summary circle", "rewrite relay", "prompt sprint", "image send", "video send", "ai summary", "ai rewrite")) -> listOf(
            "Photo race - first clear snap.",
            "Clip dash - short video win.",
            "Frame hunt - find the detail.",
            "Summary circle - one-line recap.",
            "Rewrite relay - polish the draft.",
            "Prompt sprint - ask better."
        )
        offlineHas(lower, seed, listOf("pin drop", "file relay", "map dash", "vault lock", "watermark hunt", "secure sprint", "secret chat", "screen secure")) -> listOf(
            "Pin drop - share a static pin.",
            "File relay - pass the document.",
            "Map dash - race the route.",
            "Vault lock - secret chat on.",
            "Watermark hunt - find the mark.",
            "Secure sprint - FLAG_SECURE active."
        )
        offlineHas(lower, seed, listOf("spoiler race", "blur battle", "download dash", "spoiler media", "auto download")) -> listOf(
            "Spoiler race - no peeking.",
            "Blur battle - guess the shot.",
            "Download dash - save on wifi.",
            "Spoiler media is admin-gated.",
            "Auto-download can be limited."
        )
        offlineHas(lower, seed, listOf("qr quest", "contact swap", "scan sprint", "qr code", "contact card")) -> listOf(
            "QR quest - frame and scan.",
            "Contact swap - share cards carefully.",
            "Scan sprint - steady hands.",
            "QR codes stay optional.",
            "Contact cards are admin-gated."
        )
        offlineHas(lower, seed, listOf("nudge dash", "code check", "trust sprint", "nudge", "safety code")) -> listOf(
            "Nudge dash - double-tap race.",
            "Code check - compare digits offline.",
            "Trust sprint - verify safety code.",
            "Nudge is a light poke, not a call.",
            "Safety codes stay on-device."
        )
        offlineHas(lower, seed, listOf("invite race", "mention mayhem", "link hunt", "group invite", "mentions")) -> listOf(
            "Invite race starts now.",
            "Mention mayhem - tag carefully.",
            "Link hunt - find the clue.",
            "Invite link is ready.",
            "Mentions stay private in E2EE."
        )
        offlineHas(lower, seed, listOf("idea relay", "tempo tap", "translate relay", "drafts", "ai translate")) -> listOf(
            "Idea relay - pass one idea.",
            "Tempo tap - keep the beat.",
            "Translate relay - next language.",
            "Draft saved on this device.",
            "AI translate is ready."
        )
        offlineHas(lower, seed, listOf("mood meter", "focus sprint", "gratitude round", "polls", "app lock")) -> listOf(
            "Rate the mood meter 1-10.",
            "Focus sprint - set a timer.",
            "Gratitude round: one win.",
            "Quick poll is ready.",
            "App lock keeps the session private."
        )
        offlineHas(lower, seed, listOf("chat lock", "lock chat", "pin lock")) -> listOf(
            "Chat lock is on — enter PIN.",
            "Unlock when you're ready.",
            "Keep the lock PIN private."
        )
        offlineHas(lower, seed, listOf("edit message", "message edit", "typo fix")) -> listOf(
            "Edited — fixed the typo.",
            "Edit window is short, act fast.",
            "I'll edit that message."
        )
        offlineHas(lower, seed, listOf("code breaker", "silly law", "emoji math")) -> listOf(
            "Code breaker — four digits.",
            "New silly law for the group.",
            "Emoji math — solve it."
        )
        offlineHas(lower, seed, listOf("mute chat", "unmute", "notifications off")) -> listOf(
            "Muted this chat for focus.",
            "Unmute when free.",
            "Silence is intentional."
        )
        offlineHas(lower, seed, listOf("disappear", "disappearing", "auto delete", "阅后即焚")) -> listOf(
            "Timer is set — messages will vanish.",
            "Use a short timer for sensitive stuff.",
            "Disappearing messages keep history light."
        )
        offlineHas(lower, seed, listOf("impulse draw", "word scramble", "reaction duel")) -> listOf(
            "Impulse draw — lucky you?",
            "Unscramble this word.",
            "Reaction duel — pick a side."
        )
        offlineHas(lower, seed, listOf("pin chat", "pinned", "unpin")) -> listOf(
            "Pinned so I don't lose it.",
            "Unpin when done.",
            "Pin the important thread."
        )
        offlineHas(lower, seed, listOf("marked unread", "mark unread", "unread later")) -> listOf(
            "Marked unread for later.",
            "I'll clear it when I finish.",
            "Unread badge is intentional."
        )
        offlineHas(lower, seed, listOf("mirror echo", "sync clap", "fact or fiction")) -> listOf(
            "Mirror echo — reverse me.",
            "Sync clap on three.",
            "Fact or fiction — guess!"
        )
        offlineHas(lower, seed, listOf("archive", "archived", "inbox")) -> listOf(
            "Archived — ping if urgent.",
            "I'll unarchive later.",
            "Inbox zero-ish after archive."
        )
        offlineHas(lower, seed, listOf("nearby", "around me", "local people")) -> listOf(
            "Nearby is optional privacy-wise.",
            "Turn radius down if crowded.",
            "Sharing location only when needed."
        )
        offlineHas(lower, seed, listOf("debate", "emoji story", "quick poll")) -> listOf(
            "Debate flash — pick a side.",
            "Emoji story round!",
            "Quick poll — vote now."
        )
        offlineHas(lower, seed, listOf("moments", "posts", "timeline")) -> listOf(
            "Check my latest post when free.",
            "Moments can wait — chatting first.",
            "I just shared something on Moments."
        )
        offlineHas(lower, seed, listOf("block", "report", "spam", "harass")) -> listOf(
            "You can block or report if needed.",
            "Safety first — don't tolerate abuse.",
            "I can help you report this."
        )
        offlineHas(lower, seed, listOf("alphabet", "silent movie", "color word")) -> listOf(
            "Alphabet race — your turn.",
            "Silent movie round next.",
            "Color-word challenge accepted."
        )
        offlineHas(lower, seed, listOf("sticker", "表情包", "emoji pack")) -> listOf(
            "发一个合适的贴纸？",
            "这个表情包绝了。",
            "Sticker energy."
        )
        offlineHas(lower, seed, listOf("silent", "无声", "免打扰", "dnd")) -> listOf(
            "我用无声发送，不吵你。",
            "先免打扰，晚点聊。",
            "Sending silently."
        )
        offlineHas(lower, seed, listOf("watermark", "盲水印", "取证")) -> listOf(
            "截图会有盲水印痕迹。",
            "后台可提取水印信息。",
            "Forensics-ready."
        )
        offlineHas(lower, seed, listOf("打电话", "call me", "视频通话", "voice call", "facetime")) -> listOf(
            "我现在方便接电话。",
            "改文字聊也可以。",
            "Want a quick call?"
        )
        offlineHas(lower, seed, listOf("定时", "稍后发", "schedule", "remind me later")) -> listOf(
            "我设个定时消息。",
            "到点我提醒你。",
            "I'll schedule it."
        )
        offlineHas(lower, seed, listOf("群公告", "announcement", "置顶", "pin this")) -> listOf(
            "建议置顶关键信息。",
            "我来发一版群公告草稿。",
            "Pin the summary?"
        )
        offlineHas(lower, seed, listOf("阅后即焚", "view once", "看完即焚", "viewonce")) -> listOf(
            "敏感图用阅后即焚发。",
            "看完就没了，注意隐私。",
            "Sending as view-once."
        )
        offlineHas(lower, seed, listOf("实时位置", "live location", "共享位置", "share location")) -> listOf(
            "我开了实时位置，到了关。",
            "只共享一会儿。",
            "Sharing live location briefly."
        )
        offlineHas(lower, seed, listOf("机器人", "bot api", "webhook", "开发者")) -> listOf(
            "可以自助接入机器人。",
            "Webhook 记得验签。",
            "Check the bot developer docs."
        )
        offlineHas(lower, seed, listOf("markdown", "md 格式", "代码块", "fenced")) -> listOf(
            "支持 Markdown 渲染，注意管理员开关。",
            "代码块用三个反引号包起来。",
            "Markdown looks great in Maodouchat."
        )
        offlineHas(lower, seed, listOf("正在输入", "typing", "输入中", "对方在打字")) -> listOf(
            "对方输入状态可关，保护隐私。",
            "我看到你在打字了。",
            "Typing indicators are optional."
        )
        offlineHas(lower, seed, listOf("禁忌词", "taboo", "闪电回合", "两词故事")) -> listOf(
            "来局禁忌词描述吧！",
            "闪电回合，十秒开抢。",
            "Two-word story time?"
        )
        offlineHas(lower, seed, listOf("已读", "read receipt", "双勾", "seen")) -> listOf(
            "已读回执可在后台关闭。",
            "我这边已读了。",
            "Read receipts are privacy-gated."
        )
        offlineHas(lower, seed, listOf("在线", "presence", "last seen", "最后在线")) -> listOf(
            "在线状态也可关闭，更私密。",
            "我现在在线。",
            "Presence is optional."
        )
        offlineHas(lower, seed, listOf("悄悄话", "whisper", "倒计时抢答", "表情对决")) -> listOf(
            "来局悄悄话挑战！",
            "倒计时抢答开始。",
            "Emoji duel — pick a side!"
        )
        offlineHas(lower, seed, listOf("星标", "star message", "收藏消息", "bookmark")) -> listOf(
            "重要消息可以星标。",
            "星标列表稍后一起看。",
            "I'll star that for later."
        )
        offlineHas(lower, seed, listOf("导出聊天", "export chat", "备份聊天", "export history")) -> listOf(
            "导出前注意密聊限制。",
            "管理员可关闭导出。",
            "Export is privacy-gated."
        )
        offlineHas(lower, seed, listOf("地理猜猜", "geo guess", "表情记忆", "极速报菜名")) -> listOf(
            "来局地理猜猜！",
            "表情记忆，看谁记得牢。",
            "Rapid fire — go!"
        )
        offlineHas(lower, seed, listOf("转发", "forward", "转给", "share message")) -> listOf(
            "转发注意密聊限制。",
            "管理员可关闭转发。",
            "Forwarding is privacy-gated."
        )
        offlineHas(lower, seed, listOf("全局搜索", "global search", "搜聊天记录", "search chats")) -> listOf(
            "全局搜索可在后台关闭。",
            "我帮你关键词定位。",
            "Search is optional."
        )
        offlineHas(lower, seed, listOf("一词接龙", "极速心算", "故事种子", "one word")) -> listOf(
            "来局一词接龙！",
            "极速心算，看谁快。",
            "Story seed — your line!"
        )
        offlineHas(lower, seed, listOf("加好友", "好友申请", "friend request", "加个好友")) -> listOf(
            "我发了好友申请。",
            "好友申请可后台关闭。",
            "Friend request sent."
        )
        offlineHas(lower, seed, listOf("文件夹", "会话分组", "chat folder", "整理会话")) -> listOf(
            "可以用文件夹整理会话。",
            "文件夹功能可后台关闭。",
            "Folders keep chats tidy."
        )
        offlineHas(lower, seed, listOf("纯表情", "盲抽", "二选一加强", "emoji only")) -> listOf(
            "来局纯表情挑战！",
            "盲抽表情，猜猜是啥。",
            "Would you rather — round 2!"
        )
        else -> listOf(
            "收到，我晚点仔细回你。",
            "明白了。",
            "嗯嗯，继续说。"
        )
    }
    val toned = when (tone) {
        "formal" -> base.map {
            it.replace("～", "。").replace("嗯嗯，", "好的，")
        }
        "concise" -> base.map { it.take(14) }
        "humorous" -> base.map { "$it :)" }
        "warm" -> base.map { if (it.endsWith("。") || it.endsWith("～")) it else "$it～" }
        else -> base
    }
    return toned.distinct().take(4)
}

private fun offlineHas(lower: String, seed: String, keys: List<String>): Boolean =
    keys.any { key -> key.lowercase() in lower || key in seed }

