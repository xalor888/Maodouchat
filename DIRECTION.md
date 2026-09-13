# DIRECTION — Maodouchat 该往哪走

> 本文由 keepgoal 链的自主 Agent 撰写，基于对工作区的实际读取与命令验证，不基于 README 的自述。
> 撰写时间：本轮 bootstrap。所有数字均为本轮 `grep`/`ls`/`git` 实测，可复现。

---

## 0. 我实测到的现状

| 事实 | 实测值 | 来源 |
|------|--------|------|
| 已跟踪文件 | 1576 | `git ls-files \| wc -l` |
| 服务端测试文件 / 用例基线 | 98 个文件 / 348 绿 | `find server/src/test -name '*.kt'`；README 声明的基线 |
| 客户端 JVM 测试文件 / 用例基线 | 294 个文件 / 1117 绿 | `find app/src/test -name '*.kt'` |
| 仪器测试（androidTest） | **4 个文件** | `find app/src/androidTest -name '*.kt'` |
| 自审清单体量 | 94,829 字节 | `docs/full-project-refactor-checklist.md` |
| 服务端 `plugins/` 内 `transaction {` | **75 处 / 18 个文件** | `grep -rn "transaction\s*{" plugins/` |
| 最差单文件 | `AdminExportsRouting.kt` 26 处（1110 行） | 同上 |
| `plugins/` 中 import Exposed 的文件 | 36 | `grep -rln org.jetbrains.exposed plugins/` |
| `repository/` → `plugins/` 反向依赖 | **3 处 / 2 个文件** | `BotRepository.kt:18`、`RateLimitStatsRepository.kt:4-5` |
| `repository/` 目录下的 `*Service.kt` | **16 个**（共 72 文件） | `ls repository/ \| grep -c Service.kt` |

关键补充事实：

1. **在途改动未提交**：`git status` 有 8 个文件的改动（B03 signal device backfill，migration v5 + 新测试
   `SignalDeviceBackfillMigrationTest.kt`）。本轮已实测：`../gradlew test --tests "*SignalDeviceBackfillMigrationTest*"
   --tests "*MigrationRunnerTest*"` → BUILD SUCCESSFUL，新测试**真实执行**（非 UP-TO-DATE）。
2. **自审清单刚刚自我下调**：同一份 diff 把 U01/U02/GroupPlay/B13 四项从 `[x]` 改成 `[~]`，理由是实测出来的
   行数与直连 DAO 数。这说明**此前的 `[x]` 曾是乐观叙述**，清单本身正在变诚实——这是好事，也是信号。
3. **Q01–Q06 六节硬门槛几乎全空**：无 PG 为真源的并发矩阵、无迁移版本矩阵、无双账号双设备离线 E2E、
   无 Compose/截图/无障碍回归、无性能基准。`app/src/androidTest` 只有 4 个文件。
4. **已有的静态门禁只在客户端一侧**：`core/testing/ArchitectureTest.kt`（ArchUnit，2 条规则）+
   根 `build.gradle.kts` 的 `checkArchitecture`（模块依赖单向）。**服务端没有任何架构门禁**。
5. **CI 已经能跑 `server:test` + `postgresIntegrationTest` + 三个浏览器 E2E**——加一个服务端架构
   JUnit 测试即可自动进门禁，**无需改 CI**。

---

## 1. 我的判断

**Maodouchat 的「功能库存」远远领先于「证据库存」。**

代码里已经有的东西，比能证明的东西多得多。这不是懒，是顺序问题：这个项目一路以来先建功能、再写
自审、最后才发现自审里的 `[x]` 有一批经不起实测（B13 的「路由事务清零」、U01 的「职责收敛」、
群玩法的「拆成独立 feature」都是本轮被自己推翻的）。

一个端到端加密 IM，最不能承受的失败模式恰恰是**「宣称 E2EE 而某条路径其实没有」**。而这种失败
无法靠再多写一个功能来避免，只能靠证据。同理，「route→service→repository」这条中心契约如果只写在
文档里、而 `plugins/` 里躺着 75 个 `transaction {`，那它就不是契约，是愿望。

所以方向不是「再做什么新功能」，也不是「把文件改小」——清单第 15 节自己已经写清楚了：

> 完成标准不是文件变小，也不是新类数量增加，而是：职责只有一个 owner、状态只有一个真相源、
> 所有入口走同一事务与权限边界、旧路径真正删除，并在真实离线和多设备环境中证明可以恢复。

我完全同意这句话，并把它当作本链的判据。**我要做的是把这句话从散文变成会失败的东西。**

---

## 2. 方向：三条轨道，按顺序推进

### 轨道 A（先做）——让契约可执行

原则：**任何一条架构断言，要么有会失败的测试，要么不许写进文档当已完成。**

- 服务端补 `ServerArchitectureTest`：把 `plugins/` 直写事务、`repository/`→`plugins/` 反向依赖、
  `repository/` 里的 service 错放，全部变成**棘轮（ratchet）**——冻结当前违规数，只许降不许升，
  新增一处立刻红。
- 客户端已有的 ArchUnit 门禁同样接棘轮，把 `ChatDetailRoute` 这类「Composable 内写库」纳入可测边界。
- 每个目标结束前，清单里对应的 `[x]/[~]` 必须由**可执行的证据**支撑，否则降级。

为什么先做这个：它一次性把后面所有目标从「我记得我改好了」变成「门禁替我记着」。

### 轨道 B（主体）——把断言换成证据

按「风险 × 可验证性」排序，而不是按文件大小排序：

1. **服务端中心契约**：`AdminExportsRouting.kt`（1110 行 / 26 事务 / 内联 Exposed SQL + CSV 映射）
   → `AdminExportService` + `AdminExportRepository`。这是全项目最大的单点架构缺口。
2. **反向依赖归零**：`repository/` 里的 service 搬到 `service/`，`plugins` 的常量/工具下沉到中立包。
3. **E2EE 不变量harness**：把 `docs/messaging-v2-architecture.md` 里的 23 条不变量，逐条变成服务端
   可执行断言（设备覆盖、密文落库、ACK 幂等、无明文入服务端、群 epoch 失效）。这是产品命门。
4. **迁移矩阵**：空库 / 最后生产版本 / 重复 / 中断 / 回滚，在 PostgreSQL 上跑通，H2 只做快测。
5. **双账号双设备离线 E2E**：真机/模拟器矩阵，覆盖杀进程、网络切换、新设备、Sender Key repair。

### 轨道 C（最后）——客户端热点与体验

`ChatDetailRoute.kt`（5061 行 composable，内含 `while(true)` 每 60s 写库）、`ChatDetailViewModel.kt`
（3131 行）、`GroupPlayPolicy.kt`（2298 行，且有同名重复文件）。这些是本项目最贵的债，但**放在最后做**：
在边界门禁就位之前动它们，只会重演「表面收敛、实质未动」。

只有 A、B 走稳，才谈无障碍/截图/性能回归（P10/Q03/Q05）。

---

## 3. 里程碑与判据

| # | 里程碑 | 完成判据（必须可执行） |
|---|--------|------------------------|
| M1 | 可执行契约就位 + 在途 B03 落地 | 服务端架构棘轮测试存在且通过；全量 server test ≥348 绿；B03 提交 |
| M2 | 服务端中心边界闭环 | `plugins/` 事务数单调下降且 `AdminExportsRouting` 不再直写 Exposed；反向依赖 0 |
| M3 | E2EE 不变量有证据 | messaging-v2 不变量中可服务端验证的部分 100% 有测试，且测试在改动实现时会红 |
| M4 | 迁移与并发以 PG 为真源 | 迁移矩阵（空/旧/重复/中断/回滚）在 PG 上绿 |
| M5 | 双账号双设备离线 E2E | 矩阵脚本可在本机复现，失败会给出可诊断输出 |
| M6 | 客户端热点收敛 | 门禁覆盖 `ChatDetail*`/`GroupPlayPolicy`，旧路径真正删除 |

**反面判据（出现即视为未完成）**：只改文档不改代码；只加测试不改行为；把 `[x]` 写成叙述而没有命令
输出支撑；「我检查了，一切正常」。

---

## 4. 边界与不做的事

- **不改产品边界**：仍不做 iOS/桌面/网页、支付红包、小程序、大群 SFU。
- **不为了好看而重构**：文件变小不是目标，职责唯一 owner 才是。
- **不宣称 Telegram/微信/QQ 对等，也不宣称完整 Signal 对等**。
- **不动生产库**、不把任何密码写入仓库/记忆/文档。
- **不跳过验证**：真机/headless 的差异已知（headless 整程约 2.2s 会与插件定时器赛跑，约 1/3 概率
  把没做的事报成做完了），所以端到端验证必须起长驻实例再轮询。

---

## 5. 第一个目标

见 `keepgoal_next_goal`：落地 M1。
