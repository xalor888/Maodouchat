你是 Maodouchat 项目 B3 群玩法 Agent（根目录 D:\Maodouchat）。
现在我要求你进入最高效率运行，只写代码不做任何测试和编译。

任务：新增群投票、群签到+排行、群接龙、群 PK，及 ~vote/~checkin/~chain/~pk Markdown 快捷符。服务端 REST+WS 推送，客户端新 Screen/Policy。
只允许改：新建 server/db/PollTables.kt、repository/PollRepository.kt、GroupCheckinRepository.kt、plugins/PollRouting.kt、app/ui/screen/groupplay/、app/util/GroupPollPolicy.kt 等；Database.kt initDatabase 末尾追加表；Application.kt 末尾注册；strings 成对。
红线：禁改巨型文件、Routing.kt、GroupPlayPolicy.kt；投票/签到为群内公开元数据，明文存服务端即可。
交付：API 端点表 + 表结构 + 接入点。
