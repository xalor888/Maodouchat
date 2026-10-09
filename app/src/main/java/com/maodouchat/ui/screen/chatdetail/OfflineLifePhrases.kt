package com.maodouchat.ui.screen.chatdetail

// 离线话术：日常事务/生活簇。
internal object OfflineLifePhrases {
    fun match(lower: String, seed: String): List<String>? {
        if (offlineHas(lower, seed, listOf("meeting", "会议", "开会", "sync"))) return listOf(
            "好的，我改一下时间。",
            "议程我稍后发你。",
            "Can we do a short call?"
        )
        if (offlineHas(lower, seed, listOf("price", "多少钱", "费用", "报价"))) return listOf(
            "我整理一版报价给你。",
            "方便说下预算范围吗？",
            "Let me send options."
        )
        if (offlineHas(lower, seed, listOf("photo", "图片", "照片", "看看"))) return listOf(
            "发我看看～",
            "收到，我仔细看下。",
            "Looks good!"
        )
        if (offlineHas(lower, seed, listOf("code", "bug", "报错", "崩溃", "error"))) return listOf(
            "日志发我一段。",
            "我这边复现一下。",
            "Might be a race; checking."
        )
        if (offlineHas(lower, seed, listOf("weather", "天气", "下雨", "温度"))) return listOf(
            "记得看下天气预报再出门～",
            "要不要改成室内活动？",
            "我这边帮你记着关注天气变化。"
        )
        if (offlineHas(lower, seed, listOf("deadline", "截止", "ddl", "明天交"))) return listOf(
            "截止日期我记下了，要不要拆成待办？",
            "先列三点关键路径，我陪你盯进度。",
            "需要我帮你写个简短提醒吗？"
        )
        if (offlineHas(lower, seed, listOf("travel", "出差", "高铁", "飞机", "酒店"))) return listOf(
            "行程我可以帮你整理成清单。",
            "要不要同步一下出发/到达时间？",
            "路上注意安全，到了报个平安～"
        )
        if (offlineHas(lower, seed, listOf("health", "感冒", "生病", "医院", "吃药"))) return listOf(
            "多休息喝温水，严重就去看医生。",
            "需要我帮你请假话术吗？",
            "好好照顾自己，别硬撑。"
        )
        if (offlineHas(lower, seed, listOf("weekend", "周末", "假期", "vacation", "holiday"))) return listOf(
            "周末有什么安排？",
            "好好休息，周一见。",
            "Any fun plans this weekend?"
        )
        if (offlineHas(lower, seed, listOf("congrats", "恭喜", "祝贺", "庆祝", "offer"))) return listOf(
            "太棒了，恭喜你！",
            "值得好好庆祝一下。",
            "Congrats — well earned!"
        )
        if (offlineHas(lower, seed, listOf("traffic", "堵车", "迟到", "晚到", "delay"))) return listOf(
            "注意安全，到了说一声。",
            "没关系，我可以等你。",
            "Safe travels, take your time."
        )
        if (offlineHas(lower, seed, listOf("food", "吃饭", "外卖", "餐厅", "lunch", "dinner"))) return listOf(
            "要一起点外卖吗？",
            "我可以帮你列几个选项。",
            "I'm hungry too — any preference?"
        )
        if (offlineHas(lower, seed, listOf("game", "游戏", "开黑", "上分", "match"))) return listOf(
            "现在开一局？",
            "我准备好了，你定模式。",
            "Queue up, I'm in."
        )
        if (offlineHas(lower, seed, listOf("movie", "电影", "剧", "追剧", "netflix"))) return listOf(
            "有推荐的片单吗？",
            "今晚一起看？",
            "Send the title, I'll check."
        )
        if (offlineHas(lower, seed, listOf("work", "加班", " ent", "项目", "deadline"))) return listOf(
            "进度我记下了，需要帮忙拆任务吗？",
            "先聚焦最关键的一件事。",
            "Want a quick status checklist?"
        )
        if (offlineHas(lower, seed, listOf("money", "转账", "付款", "账单", "pay"))) return listOf(
            "金额确认后我再操作。",
            "发我账单明细～",
            "I'll confirm and get back."
        )
        if (offlineHas(lower, seed, listOf("birthday", "生日快乐", "过生"))) return listOf(
            "生日快乐！今天过得开心点。",
            "要不要一起安排个小庆祝？",
            "送你一个虚拟蛋糕"
        )
        if (offlineHas(lower, seed, listOf("study", "学习", "考试", "exam", "homework"))) return listOf(
            "要不要一起复盘一下重点？",
            "先休息五分钟再继续。",
            "I can quiz you on the hard parts."
        )
        if (offlineHas(lower, seed, listOf("sport", "跑步", "健身", "gym", "workout"))) return listOf(
            "今天练哪一块？",
            "加油，注意拉伸。",
            "Send me your PR, I want to cheer."
        )
        if (offlineHas(lower, seed, listOf("music", "歌", "playlist", "演唱会"))) return listOf(
            "发我歌单听听。",
            "这首循环了好几遍。",
            "Any new recommendations?"
        )
        if (offlineHas(lower, seed, listOf("pet", "猫", "狗", "铲屎", "puppy", "kitty"))) return listOf(
            "毛孩子今天乖不乖？",
            "求吸猫/吸狗现场。",
            "Pet tax please "
        )
        if (offlineHas(lower, seed, listOf("secret", "密聊", "加密", "e2ee"))) return listOf(
            "敏感内容我们用密聊说。",
            "记得开阅后即焚。",
            "I'll keep this private."
        )
        if (offlineHas(lower, seed, listOf("sleep", "失眠", "困", "熬夜", "insomnia"))) return listOf(
            "早点休息，明天再说。",
            "我先不打扰你了。",
            "Sleep well — talk tomorrow."
        )
        if (offlineHas(lower, seed, listOf("coffee", "咖啡", "奶茶", "tea"))) return listOf(
            "来一杯提神？",
            "我请你喝。",
            "Coffee or tea?"
        )
        if (offlineHas(lower, seed, listOf("rain", "下雪", "台风", "storm"))) return listOf(
            "出门记得带伞。",
            "注意安全。",
            "Stay dry out there."
        )
        if (offlineHas(lower, seed, listOf("meeting cancel", "取消", "改期", "reschedule"))) return listOf(
            "那我们另约时间。",
            "收到，我改日历了。",
            "No problem — propose a new slot."
        )
        if (offlineHas(lower, seed, listOf("flight", "航班", "高铁", "train", "airport"))) return listOf(
            "一路顺风，落地报平安。",
            "需要我帮你看时刻表吗？",
            "Safe travels — ping me when you land."
        )
        if (offlineHas(lower, seed, listOf("wifi", "网络", "断网", "lag", "卡顿"))) return listOf(
            "可能是网络波动，稍后再试。",
            "我这边也有点卡。",
            "Try switching networks?"
        )
        if (offlineHas(lower, seed, listOf("gift", "礼物", "惊喜", "present"))) return listOf(
            "要不要一起挑个礼物？",
            "保密，别剧透～",
            "I have an idea — call me."
        )
        if (offlineHas(lower, seed, listOf("interview", "面试", "offer", "hr"))) return listOf(
            "祝你顺利，稳住发挥。",
            "需要我帮你过一遍常见问题吗？",
            "You've got this — knock them out."
        )
        if (offlineHas(lower, seed, listOf("battery", "没电", "充电", "low battery"))) return listOf(
            "快没电了，我先去充电。",
            "回头再聊～",
            "Powering up — brb."
        )
        if (offlineHas(lower, seed, listOf("map", "迷路", "导航", "lost", "directions"))) return listOf(
            "发我定位，我帮你看。",
            "别急，先找个地标。",
            "Share your pin, I'll guide you."
        )
        if (offlineHas(lower, seed, listOf("package", "快递", "外卖到了", "delivery"))) return listOf(
            "收到了说一声。",
            "我下楼拿。",
            "I'll grab it."
        )
        if (offlineHas(lower, seed, listOf("//", "code review", "pr ", "merge"))) return listOf(
            "我晚点看你的 PR。",
            "有冲突先 rebase 一下。",
            "LGTM with nits — shipping."
        )
        if (offlineHas(lower, seed, listOf("cook", "做饭", "菜谱", "recipe"))) return listOf(
            "今晚想吃什么？",
            "发我菜谱链接～",
            "I can help plan the menu."
        )
        if (offlineHas(lower, seed, listOf("plant", "浇花", "绿植", "garden"))) return listOf(
            "别忘了浇水。",
            "新芽发了吗？",
            "Plant tax photos please."
        )
        if (offlineHas(lower, seed, listOf("book", "读书", "小说", "reading"))) return listOf(
            "最近在看什么？",
            "读完安利我。",
            "Drop the title — adding to my list."
        )
        if (offlineHas(lower, seed, listOf("gym fail", "没去练", "偷懒", "rest day"))) return listOf(
            "休息也是训练的一部分。",
            "明天补上就好。",
            "Rest day accepted."
        )
        if (offlineHas(lower, seed, listOf("password", "密码", "2fa", "验证码", "totp"))) return listOf(
            "别在群里发验证码。",
            "建议开 TOTP 两步验证。",
            "Reset via secure channel only."
        )
        if (offlineHas(lower, seed, listOf("screenshot", "截图", "录屏", "screen record"))) return listOf(
            "密聊请勿截图。",
            "有盲水印可追溯。",
            "Use view-once if sensitive."
        )
        if (offlineHas(lower, seed, listOf("budget", "预算", "省钱", "理财"))) return listOf(
            "我们列个简单预算表。",
            "先区分必要与可选开支。",
            "Want a 3-line budget?"
        )
        if (offlineHas(lower, seed, listOf("doctor", "医院", "挂号", "clinic"))) return listOf(
            "早去排队，记得带证件。",
            "需要我陪你吗？",
            "Feel better soon."
        )
        if (offlineHas(lower, seed, listOf("parking", "停车", "挪车", "garage"))) return listOf(
            "我马上挪一下。",
            "发我位置。",
            "On my way to move it."
        )
        if (offlineHas(lower, seed, listOf("vpn", "代理", "翻墙", "proxy"))) return listOf(
            "注意账号安全，别分享节点。",
            "优先用官方通道。",
            "Keep credentials private."
        )
        return null
    }
}
