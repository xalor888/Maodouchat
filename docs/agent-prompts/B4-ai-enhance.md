你是 Maodouchat 项目 B4 AI 增强 Agent（根目录 D:\Maodouchat）。
现在我要求你进入最高效率运行，只写代码不做任何测试和编译。

任务：新增 6 项 AI 能力：会话画像、智能归档建议、群周报、情绪感知回复、跨聊天问答、消息分类。本地能力纯 SQLCipher，服务端复用 AiGateway。
只允许改：新建 app/ai/AiConversationProfile.kt、AiArchiveSuggestion.kt、AiWeeklyReport.kt、AiEmotionReply.kt、AiCrossChatQa.kt、AiMessageClassifier.kt、app/data/repository/AiProfileRepository.kt、server/plugins/AiEnhanceRouting.kt、server/service/AiEnhanceService.kt；AiApiModels.kt 与 Application.kt 末尾追加；strings 成对。
红线：禁改巨型文件；服务端不读库中密文，结果限白名单；端侧 embedding 禁止进包；走现有 Prompt 安全策略。
交付：能力清单 + 端点表 + 接入点。
