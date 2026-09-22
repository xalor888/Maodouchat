# DIRECTION — Maodouchat 该往哪走

> 本文由 keepgoal 链的自主 Agent 撰写，基于对工作区的实际读取与命令验证，不基于 README 的自述。
> 初版撰写于 bootstrap 轮；**§0 的数字已由 G171b 全量重测刷新**（此前每个数都过期了）。
> 本文所有数字都应可由一条 `grep`/`ls`/`git`/`wc` 复现——不可复现的就是该被质疑的。

---

## 0. 我实测到的现状

> 本节数字由 **G171b 全量重测**（每一条都有对应命令，可复现）。此前版本停在 bootstrap 轮，
> 表里每个数都过期了——最刺眼的两条：`plugins/` 的 `transaction {` 从 75 处变成 **0**，
> 「GroupPlayPolicy 有同名重复文件」已经只剩 **1 个**。

| 事实 | 实测值 | 来源 |
|------|--------|------|
| 已跟踪文件 | 1792 | `git ls-files \| wc -l`
| 服务端测试文件 / 用例 | 118 个 / **467 绿** | `find server/src/test -name '*.kt'`；`server/build/test-results/test/*.xml` 汇总
| 客户端 JVM 测试文件 / 用例 | 358 个 / **2057 绿** | `find app/src/test -name '*.kt'`；`app/build/test-results/testDebugUnitTest/*.xml` 汇总
| instrumented 测试（androidTest） | **12 个文件** | `find app/src/androidTest -name '*.kt'` |
| 自审清单体量 | 886,279 字节 | `wc -c docs/full-project-refactor-checklist.md`
| `plugins/` 内 `transaction {` | **0 处 / 0 个文件** | `grep -rho 'transaction {' server/.../plugins/`（M2 已闭环） |
| 最差单文件 | `ChatDetailRoute.kt` **3433 行** | `wc -l` |
| `plugins/` 中 import Exposed 的文件 | 18 | `grep -rl org.jetbrains.exposed plugins/` |
| `repository/` → `plugins/` 反向依赖 | **0 处** | `grep -rn 'com.maodouchat.server.plugins' repository/ \| grep -c import` |
| `repository/` 目录下的 `*Service.kt` | **16 个**（共 78 文件） | `ls repository/` |

**四套测试合计 2426 例全绿**（app JVM 1918 + server 462 + PG 集成 19 + 双设备 E2E 27），
最近一次全量复跑是 G170b。

### G63 至今的成果（客户端热点线）

- **6 个热点文件已彻底还清并删除**：`ChatDetailComponents.kt`(5337→0)、
  `SettingsSubScreens.kt`(4588→0)、`ExploreSubScreens.kt`(1588→0)、
  `SettingsSubViewModels.kt`(1860→0)、`MediaMessageBubbles.kt`(1312→0)、
  `ChatDetailMiscDialogs.kt`(1133→0)；
- **26 个源文件在监行数上限**（只许降不许升），判据是「行数排名前 20」而非固定阈值
  （G164b：阈值是个要人工反复调的旋钮，1100→1000→950… 是无限回归）；
- `ChatDetailRoute.kt` 5061 → **3433**、内联 `AlertDialog` 5 → **0**（12 个弹窗全部抽出）；
- **四套门禁的源码文本判决全部先剥注释**（`stripComments`）——这个坑踩过四次
  （G155b/G156b/G157b/G167b），约定写在本文 3.5 节。

关键补充事实：

1. **剩下三个大文件，用「抽声明」已经拆不动了**（G153b/G166b 实测）：
   `ChatDetailViewModel.kt`(3102) 是 `class`、`ExtendedOutlinedIcons.kt`(2678) 是 vendored 图标
   （57 个图标**全部有引用**，无死代码可删）、`GroupPlayPolicy.kt`(2209) 是 542 个小函数的 `object`
   （最大的函数也只有 12 行，没有肥块可抽）。要再降行数只能走「抽 class 再委托」的大改。
2. **`GroupPlayPolicy` 有 297 / 542 个成员在全仓库零引用**（G167b 已固化成棘轮，
   只许降不许升）。删不删是产品决策——它们看起来像内容路线图（转盘/宾果/抛硬币/记忆配对……）。
3. **已有的门禁都在 CI 会跑的地方**：`core/testing/ArchitectureTest.kt`（ArchUnit）+
   `app/src/test/.../ClientArchitectureTest.kt`（17 条）+
   `server/src/test/.../ServerArchitectureTest.kt` + `MessagingInvariantTraceabilityTest`。
   ⚠️ G156b 曾发现 `:core:testing` 是个**孤儿模块**（没人依赖、CI 不调用），
   那里有过一个 3 用例全红的死门禁——已删除。

## 1. 我的判断

**Maodouchat 的「功能库存」远远领先于「证据库存」。**

代码里已经有的东西，比能证明的东西多得多。这不是懒，是顺序问题：这个项目一路以来先建功能、再写
自审、最后才发现自审里的 `[x]` 有一批经不起实测（B13 的「路由事务清零」、U01 的「职责收敛」、
群玩法的「拆成独立 feature」都是本轮被自己推翻的）。

一个端到端加密 IM，最不能承受的失败模式恰恰是**「宣称 E2EE 而某条路径其实没有」**。而这种失败
无法靠再多写一个功能来避免，只能靠证据。同理，「route→service→repository」这条中心契约如果只写在
文档里、而 `plugins/` 里躺着 75 个 `transaction {`，那它就不是契约，是愿望。
（**G171b 注**：这句话在 G171b 已经过时了——`plugins/` 的 `transaction {` 现在是 **0 处**，
M2 闭环了。当时它是「愿望」，现在它是有门禁守着的事实：
`ServerArchitectureTest` 7 条 + `MessagingInvariantTraceabilityTest` 4 条。
保留原文是为了让「曾经只是愿望」这件事可追溯。）

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

## 3.5 工程约定：用源码文本做判决的门禁，**第一步必须剥注释**

> 这一节是 G155b / G156b / G157b 三轮各踩一次同一个坑之后补上的。三艘船撞的是同一座礁。

本项目有四套「读源码文本下结论」的门禁：`ClientArchitectureTest`（app）、
`ServerArchitectureTest`（server）、`MessagingInvariantTraceabilityTest`（server）、
`ArchitectureTest`（core/testing，ArchUnit）。

**规则：判定「源码里有没有某个符号/标注/声明」之前，先剥掉行注释与块注释。**

三次真实的撞礁记录：

| 轮次 | 门禁 | 撞礁方式 |
|---|---|---|
| G155b | `MessagingInvariantTraceabilityTest` | `text.contains("fun \`testName\`")` 纯子串匹配。把某个测试的 `fun` 行注释掉 → 文档引用照样解析成功、门禁全绿，而那个用例根本不执行 |
| G156b | `ClientHotspotRatchetTest` | 数原始文本里的 `MaodouchatApp`。G184–G192 抽 dialog 时给每个新文件写「**拆解约束**：不 import `MaodouchatApp`」——这句 KDoc 本身含该符号，于是 **48 个文件因声明自己不碰单例而被计成违规**（虚增 55%） |
| G157b | `ClientArchitectureTest` 新增用例 | `text.contains("@ArchTest")`。把 `@ArchTest` 注释掉 → 断言仍通过 |

三条共同点：**注释不是代码，但 `contains` 分不清**。而门禁的职责恰恰是「文档/注释说了不算，
证据要真实存在」——用会误信注释的手段去检查「有没有真实证据」，是自相矛盾的。

落地要求（新增或修改第四套时逐条对照）：

1. 先剥注释再匹配。`app` 侧用 `ClientArchitectureTest.stripComments`，
   `server` 侧用 `MessagingInvariantTraceabilityTest.stripComments`（两份实现，
   因为 `server/` 是独立 Gradle 构建，目前不复用；改任一份时同步另一份的语义）。
2. **剥注释本身要过负控制**：把目标符号注释掉，门禁必须变红。G157b 就是这么发现
   自己第 3 次踩坑的——第一版 NC 没红，才回去修。
3. KDoc 里**不要贴** `/*`、`*/`、`//` 这些注释定界符的实例——它们会真的结束注释
   （G156b 编译报 Unclosed comment）。要写就写「斜线星」或转义。

---

## 4. 边界与不做的事

- **不改产品边界**：仍不做 iOS/桌面/网页、支付红包、小程序、大群 SFU。
- **不为了好看而重构**：文件变小不是目标，职责唯一 owner 才是。
- **不宣称 Telegram/微信/QQ 对等，也不宣称完整 Signal 对等**。
- **不动生产库**、不把任何密码写入仓库/记忆/文档。
- **不跳过验证**：真机/headless 的差异已知（headless 整程约 2.2s 会与插件定时器赛跑，约 1/3 概率
  把没做的事报成做完了），所以端到端验证必须起长驻实例再轮询。

---

## 4.5 工程工具（每轮收尾用）

两个脚本，**每轮改完代码后跑**（G188b / G189b / G190b 三次迭代出来，
治的是我反复犯的「顺序靠记忆」和「推导而非实测」）：

| 命令 | 干什么 |
|---|---|
| `python3 scripts/sync-direction-numbers.py --check` | 只比对 §0 数字与实测，不一致退出码 1（可当卡点） |
| `python3 scripts/sync-direction-numbers.py --write` | 按实测改写 §0 表格（只换数字，不动 `**bold**`/括注） |
| `bash scripts/finish-round.sh <条目文件> "提交信息..."` | **一条命令收尾一整轮**：全量 app → 全量 server → 追加台账 → 同步 §0 → 复核门禁 → 提交 |
| `bash scripts/finish-round.sh <条目文件> "..." --skip-tests` | 刚跑完全量时用，省 12 分钟 |

⚠️ **`--skip-tests` 只在紧接着全量跑过之后有效**：脚本从 `build/test-results`
读用例数，若上一次是 `--tests '*某个类'` 的过滤跑，合理性守卫会拒绝同步
（不拿不完整快照当真相）。

### 真机 UI 辅助工具（本地开发用，非 CI 门禁）

`scripts/qa-ui.py`——adb 驱动的轻量真机检查，**盘「无人调用资产」时救回来的**
（G199b/G200b：它有 `__pycache__` 说明被跑过，但全仓没有任何地方引用它，
连 docstring 里写的用法都是崩的）：

```bash
python3 scripts/qa-ui.py dump            # 打印当前界面可交互节点（文本 + content-desc + 坐标）
python3 scripts/qa-ui.py shot NAME       # 截图到 .qa-live/NAME.png（该目录已 gitignore）
python3 scripts/qa-ui.py tap X Y
python3 scripts/qa-ui.py text STR        # 输入文本
python3 scripts/qa-ui.py log -n 200      # 打印最近崩溃/异常日志
python3 scripts/qa-ui.py start           # 冷启动 app
```

⚠️ 实测踩过：`log` 只认位置参数（`log 50`），照 docstring 敲 `log -n 50`
会把 `"-n"` 丢给 `int()` 直接崩。G200b 已改成两种写法都接受。
真机设备 serial 可用环境变量 `QA_SERIAL` 指定（默认用唯一在线设备）。

**为什么值得写在这一节**：这三个工具本身就可能重蹈「Robolectric 不可用」
那条注释的覆辙——写在没人看的地方，过期/被忘掉都没人发现（G181b 的教训）。
放在每个回合都会读的 DIRECTION.md 里，它们才真的会被用。

---

## 5. 第一个目标

见 `keepgoal_next_goal`：落地 M1。
