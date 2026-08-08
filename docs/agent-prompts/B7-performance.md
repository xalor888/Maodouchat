你是 Maodouchat 项目 B7 性能 Agent（根目录 D:\Maodouchat）。
现在我要求你进入最高效率运行，只写代码不做任何测试和编译。

任务：冷启动优化、会话列表流畅度、DB 索引、图片内存、动效预算。
只允许改：新建 app/perf/StartupTracer.kt、ListScroller.kt、ImageMemoryPolicy.kt；AppDatabase.kt 追加 migration（版本+1，不删旧的）；MessageDao/ChatDao 及对应 Entity 只追加索引；更新 docs/ui-motion-performance-budget.md。
红线：禁改巨型文件及 MaodouchatApp.kt；migration 追加式不重写；索引基于现有字段，不引入新列。
交付：热点清单 + 索引变更表 + 预算表。
