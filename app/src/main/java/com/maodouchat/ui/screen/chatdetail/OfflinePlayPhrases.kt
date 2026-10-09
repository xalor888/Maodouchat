package com.maodouchat.ui.screen.chatdetail

// 离线话术：群玩/应用功能簇。
internal object OfflinePlayPhrases {
    fun match(lower: String, seed: String): List<String>? {
        if (offlineHas(lower, seed, listOf("backup", "备份", "导出聊天", "export chat"))) return listOf(
            "密聊内容不建议明文导出。",
            "可用加密备份方案。",
            "Prefer encrypted backups only."
        )
        if (offlineHas(lower, seed, listOf("pin message", "unpin message", "message pin"))) return listOf(
            "Pinned for the group.",
            "Unpinned — no longer sticky.",
            "Pin the important update."
        )
        if (offlineHas(lower, seed, listOf("revoke message", "delete message", "unsend"))) return listOf(
            "Revoked — pretend you didn't see it.",
            "Deleted on my side.",
            "I'll unsend that."
        )
        if (offlineHas(lower, seed, listOf("pin the mood", "revoke rush", "secret signal"))) return listOf(
            "Pin the mood for today.",
            "Revoke rush starts now.",
            "Secret signal received."
        )
        if (offlineHas(lower, seed, listOf("doc hunt", "meaning race", "insight sprint", "ai file", "semantic search", "analyze file"))) return listOf(
            "Doc hunt - find the clause.",
            "Meaning race - semantic win.",
            "Insight sprint - one key takeaway.",
            "AI file analysis is admin-gated.",
            "Semantic search can be limited."
        )
        if (offlineHas(lower, seed, listOf("pixel quest", "assist circle", "decision dash", "ai analyze", "group assistant", "image analyze"))) return listOf(
            "Pixel quest - find the clue in the photo.",
            "Assist circle - group AI recap.",
            "Decision dash - pick next steps.",
            "AI image analysis is admin-gated.",
            "Group assistant can be limited."
        )
        if (offlineHas(lower, seed, listOf("suggest circle", "voice race", "reply sprint", "ai suggest", "ai transcribe", "suggest replies"))) return listOf(
            "Suggest circle - share a quick reply idea.",
            "Voice race - short clear note.",
            "Reply sprint - three options fast.",
            "AI suggest replies is admin-gated.",
            "AI transcribe can be limited."
        )
        if (offlineHas(lower, seed, listOf("photo race", "clip dash", "frame hunt", "summary circle", "rewrite relay", "prompt sprint", "image send", "video send", "ai summary", "ai rewrite"))) return listOf(
            "Photo race - first clear snap.",
            "Clip dash - short video win.",
            "Frame hunt - find the detail.",
            "Summary circle - one-line recap.",
            "Rewrite relay - polish the draft.",
            "Prompt sprint - ask better."
        )
        if (offlineHas(lower, seed, listOf("pin drop", "file relay", "map dash", "vault lock", "watermark hunt", "secure sprint", "secret chat", "screen secure"))) return listOf(
            "Pin drop - share a static pin.",
            "File relay - pass the document.",
            "Map dash - race the route.",
            "Vault lock - secret chat on.",
            "Watermark hunt - find the mark.",
            "Secure sprint - FLAG_SECURE active."
        )
        if (offlineHas(lower, seed, listOf("spoiler race", "blur battle", "download dash", "spoiler media", "auto download"))) return listOf(
            "Spoiler race - no peeking.",
            "Blur battle - guess the shot.",
            "Download dash - save on wifi.",
            "Spoiler media is admin-gated.",
            "Auto-download can be limited."
        )
        if (offlineHas(lower, seed, listOf("qr quest", "contact swap", "scan sprint", "qr code", "contact card"))) return listOf(
            "QR quest - frame and scan.",
            "Contact swap - share cards carefully.",
            "Scan sprint - steady hands.",
            "QR codes stay optional.",
            "Contact cards are admin-gated."
        )
        if (offlineHas(lower, seed, listOf("nudge dash", "code check", "trust sprint", "nudge", "safety code"))) return listOf(
            "Nudge dash - double-tap race.",
            "Code check - compare digits offline.",
            "Trust sprint - verify safety code.",
            "Nudge is a light poke, not a call.",
            "Safety codes stay on-device."
        )
        if (offlineHas(lower, seed, listOf("invite race", "mention mayhem", "link hunt", "group invite", "mentions"))) return listOf(
            "Invite race starts now.",
            "Mention mayhem - tag carefully.",
            "Link hunt - find the clue.",
            "Invite link is ready.",
            "Mentions stay private in E2EE."
        )
        if (offlineHas(lower, seed, listOf("idea relay", "tempo tap", "translate relay", "drafts", "ai translate"))) return listOf(
            "Idea relay - pass one idea.",
            "Tempo tap - keep the beat.",
            "Translate relay - next language.",
            "Draft saved on this device.",
            "AI translate is ready."
        )
        if (offlineHas(lower, seed, listOf("mood meter", "focus sprint", "gratitude round", "polls", "app lock"))) return listOf(
            "Rate the mood meter 1-10.",
            "Focus sprint - set a timer.",
            "Gratitude round: one win.",
            "Quick poll is ready.",
            "App lock keeps the session private."
        )
        if (offlineHas(lower, seed, listOf("chat lock", "lock chat", "pin lock"))) return listOf(
            "Chat lock is on — enter PIN.",
            "Unlock when you're ready.",
            "Keep the lock PIN private."
        )
        if (offlineHas(lower, seed, listOf("edit message", "message edit", "typo fix"))) return listOf(
            "Edited — fixed the typo.",
            "Edit window is short, act fast.",
            "I'll edit that message."
        )
        if (offlineHas(lower, seed, listOf("code breaker", "silly law", "emoji math"))) return listOf(
            "Code breaker — four digits.",
            "New silly law for the group.",
            "Emoji math — solve it."
        )
        if (offlineHas(lower, seed, listOf("mute chat", "unmute", "notifications off"))) return listOf(
            "Muted this chat for focus.",
            "Unmute when free.",
            "Silence is intentional."
        )
        if (offlineHas(lower, seed, listOf("disappear", "disappearing", "auto delete", "阅后即焚"))) return listOf(
            "Timer is set — messages will vanish.",
            "Use a short timer for sensitive stuff.",
            "Disappearing messages keep history light."
        )
        if (offlineHas(lower, seed, listOf("impulse draw", "word scramble", "reaction duel"))) return listOf(
            "Impulse draw — lucky you?",
            "Unscramble this word.",
            "Reaction duel — pick a side."
        )
        if (offlineHas(lower, seed, listOf("pin chat", "pinned", "unpin"))) return listOf(
            "Pinned so I don't lose it.",
            "Unpin when done.",
            "Pin the important thread."
        )
        if (offlineHas(lower, seed, listOf("marked unread", "mark unread", "unread later"))) return listOf(
            "Marked unread for later.",
            "I'll clear it when I finish.",
            "Unread badge is intentional."
        )
        if (offlineHas(lower, seed, listOf("mirror echo", "sync clap", "fact or fiction"))) return listOf(
            "Mirror echo — reverse me.",
            "Sync clap on three.",
            "Fact or fiction — guess!"
        )
        if (offlineHas(lower, seed, listOf("archive", "archived", "inbox"))) return listOf(
            "Archived — ping if urgent.",
            "I'll unarchive later.",
            "Inbox zero-ish after archive."
        )
        if (offlineHas(lower, seed, listOf("nearby", "around me", "local people"))) return listOf(
            "Nearby is optional privacy-wise.",
            "Turn radius down if crowded.",
            "Sharing location only when needed."
        )
        if (offlineHas(lower, seed, listOf("debate", "emoji story", "quick poll"))) return listOf(
            "Debate flash — pick a side.",
            "Emoji story round!",
            "Quick poll — vote now."
        )
        if (offlineHas(lower, seed, listOf("moments", "posts", "timeline"))) return listOf(
            "Check my latest post when free.",
            "Moments can wait — chatting first.",
            "I just shared something on Moments."
        )
        if (offlineHas(lower, seed, listOf("block", "report", "spam", "harass"))) return listOf(
            "You can block or report if needed.",
            "Safety first — don't tolerate abuse.",
            "I can help you report this."
        )
        if (offlineHas(lower, seed, listOf("alphabet", "silent movie", "color word"))) return listOf(
            "Alphabet race — your turn.",
            "Silent movie round next.",
            "Color-word challenge accepted."
        )
        if (offlineHas(lower, seed, listOf("sticker", "表情包", "emoji pack"))) return listOf(
            "发一个合适的贴纸？",
            "这个表情包绝了。",
            "Sticker energy."
        )
        if (offlineHas(lower, seed, listOf("silent", "无声", "免打扰", "dnd"))) return listOf(
            "我用无声发送，不吵你。",
            "先免打扰，晚点聊。",
            "Sending silently."
        )
        if (offlineHas(lower, seed, listOf("watermark", "盲水印", "取证"))) return listOf(
            "截图会有盲水印痕迹。",
            "后台可提取水印信息。",
            "Forensics-ready."
        )
        if (offlineHas(lower, seed, listOf("打电话", "call me", "视频通话", "voice call", "facetime"))) return listOf(
            "我现在方便接电话。",
            "改文字聊也可以。",
            "Want a quick call?"
        )
        if (offlineHas(lower, seed, listOf("定时", "稍后发", "schedule", "remind me later"))) return listOf(
            "我设个定时消息。",
            "到点我提醒你。",
            "I'll schedule it."
        )
        if (offlineHas(lower, seed, listOf("群公告", "announcement", "置顶", "pin this"))) return listOf(
            "建议置顶关键信息。",
            "我来发一版群公告草稿。",
            "Pin the summary?"
        )
        if (offlineHas(lower, seed, listOf("阅后即焚", "view once", "看完即焚", "viewonce"))) return listOf(
            "敏感图用阅后即焚发。",
            "看完就没了，注意隐私。",
            "Sending as view-once."
        )
        if (offlineHas(lower, seed, listOf("实时位置", "live location", "共享位置", "share location"))) return listOf(
            "我开了实时位置，到了关。",
            "只共享一会儿。",
            "Sharing live location briefly."
        )
        if (offlineHas(lower, seed, listOf("机器人", "bot api", "webhook", "开发者"))) return listOf(
            "可以自助接入机器人。",
            "Webhook 记得验签。",
            "Check the bot developer docs."
        )
        if (offlineHas(lower, seed, listOf("markdown", "md 格式", "代码块", "fenced"))) return listOf(
            "支持 Markdown 渲染，注意管理员开关。",
            "代码块用三个反引号包起来。",
            "Markdown looks great in Maodouchat."
        )
        if (offlineHas(lower, seed, listOf("正在输入", "typing", "输入中", "对方在打字"))) return listOf(
            "对方输入状态可关，保护隐私。",
            "我看到你在打字了。",
            "Typing indicators are optional."
        )
        if (offlineHas(lower, seed, listOf("禁忌词", "taboo", "闪电回合", "两词故事"))) return listOf(
            "来局禁忌词描述吧！",
            "闪电回合，十秒开抢。",
            "Two-word story time?"
        )
        if (offlineHas(lower, seed, listOf("已读", "read receipt", "双勾", "seen"))) return listOf(
            "已读回执可在后台关闭。",
            "我这边已读了。",
            "Read receipts are privacy-gated."
        )
        if (offlineHas(lower, seed, listOf("在线", "presence", "last seen", "最后在线"))) return listOf(
            "在线状态也可关闭，更私密。",
            "我现在在线。",
            "Presence is optional."
        )
        if (offlineHas(lower, seed, listOf("悄悄话", "whisper", "倒计时抢答", "表情对决"))) return listOf(
            "来局悄悄话挑战！",
            "倒计时抢答开始。",
            "Emoji duel — pick a side!"
        )
        if (offlineHas(lower, seed, listOf("星标", "star message", "收藏消息", "bookmark"))) return listOf(
            "重要消息可以星标。",
            "星标列表稍后一起看。",
            "I'll star that for later."
        )
        if (offlineHas(lower, seed, listOf("导出聊天", "export chat", "备份聊天", "export history"))) return listOf(
            "导出前注意密聊限制。",
            "管理员可关闭导出。",
            "Export is privacy-gated."
        )
        if (offlineHas(lower, seed, listOf("地理猜猜", "geo guess", "表情记忆", "极速报菜名"))) return listOf(
            "来局地理猜猜！",
            "表情记忆，看谁记得牢。",
            "Rapid fire — go!"
        )
        if (offlineHas(lower, seed, listOf("转发", "forward", "转给", "share message"))) return listOf(
            "转发注意密聊限制。",
            "管理员可关闭转发。",
            "Forwarding is privacy-gated."
        )
        if (offlineHas(lower, seed, listOf("全局搜索", "global search", "搜聊天记录", "search chats"))) return listOf(
            "全局搜索可在后台关闭。",
            "我帮你关键词定位。",
            "Search is optional."
        )
        if (offlineHas(lower, seed, listOf("一词接龙", "极速心算", "故事种子", "one word"))) return listOf(
            "来局一词接龙！",
            "极速心算，看谁快。",
            "Story seed — your line!"
        )
        if (offlineHas(lower, seed, listOf("加好友", "好友申请", "friend request", "加个好友"))) return listOf(
            "我发了好友申请。",
            "好友申请可后台关闭。",
            "Friend request sent."
        )
        if (offlineHas(lower, seed, listOf("文件夹", "会话分组", "chat folder", "整理会话"))) return listOf(
            "可以用文件夹整理会话。",
            "文件夹功能可后台关闭。",
            "Folders keep chats tidy."
        )
        if (offlineHas(lower, seed, listOf("纯表情", "盲抽", "二选一加强", "emoji only"))) return listOf(
            "来局纯表情挑战！",
            "盲抽表情，猜猜是啥。",
            "Would you rather — round 2!"
        )
        return null
    }
}
