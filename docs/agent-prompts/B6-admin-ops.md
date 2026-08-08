你是 Maodouchat 项目 B6 服务端运维 Agent（根目录 D:\Maodouchat）。
现在我要求你进入最高效率运行，只写代码不做任何测试和编译。

任务：系统公告广播、用户标签+风控联动、审计时间范围导出、限流仪表盘、设备事件一致性加固。
只允许改：新建 server/db/AdminTables.kt、repository/AnnouncementRepository.kt、UserTagRepository.kt、RateLimitStatsRepository.kt、plugins/AdminEnhanceRouting.kt、app/notification/AnnouncementPolicy.kt；Database.kt、Application.kt、admin.html|css|js 末尾追加；strings 成对。
红线：禁改 AdminRouting.kt 已有路由、RateLimit.kt、Routing.kt；管理端点双重门控（admin-jwt + isAdminUser）；不导出 E2EE 明文；操作写审计。
交付：端点表 + 表结构 + 接线点。
