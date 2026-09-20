# Maodouchat 进度审计 — 2026-09-20

> 本轮为**只读通读 + 实测复核**。所有数字均来自本轮实际执行的命令，未采信任何文档自述。
> 复核对象：`main` @ `8d76cec6`（HEAD）+ 33 个未提交改动 + 6 个未跟踪文件。

---

## 0. 一句话结论

**功能库存仍然领先证据库存；但本轮发现的头号事实不是"进度多少"，而是——主干在工作区编译不过，已红的第 4 天，而自主链在同一时间窗内没有产出任何东西。**

这不是"推进中的项目"，是"停摆中的项目 + 一坨未编译的半成品改动"。

---

## 1. 硬状态（本轮实测）

| # | 检查 | 命令 | 结果 |
|---|------|------|------|
| 1 | App 主源码编译 | `./gradlew :app:compileDebugKotlin` | ✅ **BUILD SUCCESSFUL** in 19s |
| 2 | App 单测编译 | `./gradlew :app:testDebugUnitTest` | ❌ **FAILED** — `RealtimeEventPolicyTest.kt` 4 处类型错位 |
| 3 | Server 主源码编译 | `./gradlew compileTestKotlin` | ❌ **FAILED** — `:compileKotlin` 5 处错误 |
| 4 | Server 测试编译 | 同上 | ❌ 被主源码失败掩盖，未到达 |
| 5 | 服务端架构棘轮 | 基线 vs 实测 | ✅ 15 处 / 4 文件，**精确一致** |
| 6 | 客户端热点棘轮 | 基线 vs 实测 | ✅ 5048 / 3131 / 2298，**精确一致** |

结论：**客户端主源码是唯一干净的一层**。服务端主源码 + 两侧测试源码全部是红的。

---

## 2. 为什么是红的 — 5 处真实断点

全部集中在 G45–G52 那批**未提交**的服务端 M2 重构里。共性只有一句话：
**工具"下沉"到中立包的动作做完了，调用点没有跟着改；另有两处是凭空写出来的 schema。**

### 2.1 `repository/AdminManagementRepository.kt`（+235 行，0 删除）

编译器报 9 条错误，其中 3 条是**独立性质**的：

- **第 378 行 `Syntax error: Missing '}'`**。实测括号平衡：`{` 84 个 / `}` 83 个，差 1。
  （HEAD 版本：20 / 20，平衡，151 行 → 工作区 386 行。）**这个文件从未被编译过。**
- **`GroupAttachmentCommitRecords` 不存在**。该标识符全仓只出现在 `AdminManagementRepository.kt`
  自己的 4 行（import + 3 处使用），`db/` 下**没有任何一张这样的表**。这是编出来的表名。
- **`Chats.avatar` 不存在**。真实列名是 `Chats.groupAvatar`（`CoreTables.kt:55`）。

### 2.2 `repository/UserTagRepository.kt`（+17 行）

`RiskEvents.insert { it[eventType] / it[severity] / it[actionTaken] / it[details] }` —— 这 4 列**都不存在**。
真实 `RiskEvents` 表（`CoreTables.kt:538`）只有：
`id / userId / sourceValue("source") / ruleId / action / matched / referenceId / needsReview / createdAt`。

即：G50 想把"打标 + 风控事件写入"放进同一事务，方向对，但**写入的是一个虚构的表结构**。

### 2.3 `repository/BotRepository.kt:1019`（+1/-1）

`address.isAllowedWebhookAddress(allowLoopback = false)` —— 该扩展函数已被搬到
`common/WebhookSecurityUtils.isAllowedWebhookAddress(address, allowLoopback)`，这里仍按旧的扩展形式调用。

### 2.4 `service/BotWebhookService.kt:490`（+1/-1）

`postPinnedWebhookJson` 未解析 —— 同名能力已迁入 `common/WebhookSecurityUtils.kt`（`PinnedWebhookResponse`），
调用点没改。

### 2.5 `service/CallSignalingService.kt:121-122`（+3/-3）

`isValidGroupSignalMetadata(...)` 已迁到 `common/CallSignalingValidators.kt` 且**签名收窄为 3 个参数**
（`targetUserId, targetDeviceId, targetSessionId`），调用点仍传 5 个（`List<String>` / `Boolean` 混进来）。

### 2.6 客户端：`app/src/test/.../RealtimeEventPolicyTest.kt`（+33 行）

第 159/160/163/164 行实参与形参**类型整体颠倒**（`Boolean` 位置给了 `String`，反之亦然）。
同一个病：改完没编译。

### 2.7 另有 3 个未跟踪测试文件引用了不存在的类型

`PostgresPlaintextSweepIntegrationTest.kt` 报 `ChatRepository` / `AdminMessageMetadataRow` /
`AdminMessageSearchFilter` 未解析，且 `SendMessageV2Command` 的参数名对不上（`adminRepo`/`chatRepo` 不存在，
`postRepo`/`moderationRuleRepo` 缺参）。另两个未跟踪测试
（`AttachmentLimitsAndDefenseTest.kt`、`PostgresJobLeaseConcurrencyTest.kt`、`WebSocketContractTest.kt`）
因主源码先失败而未获编译结论。

**统计口径**：未提交改动 `server +374 / app +98 / docs +216 / other +1`。
其中 `AdminManagementRepository.kt` 一次 +235 行 —— 这就是那份没编译过的代码。

---

## 3. 自主链状态：已停摆

`\.keepgoal/ledger.jsonl` 共 1381 条，时间范围 `2026-09-13T22:19Z → 2026-09-20T02:28Z`。

| 指标 | 值 |
|------|-----|
| 最后一次**成功** `goal-ended` | **2026-09-17T07:22:50Z**（G52） |
| `goal-created` 之后无产出 | 09-18T05:25、09-19T03:21、09-20T02:2x（三次全部落空） |
| `all-routes-failed` 累计 | **149 次**（首次 09-14T11:50Z，最近 09-20T02:28Z） |
| 最近失败原因 | `OpenAI API error (403)`；更早为「用户额度不足 剩余 -42.96」/ `Connection error` |
| `switch-route` | 753 次（模型路由反复重试） |

即：**从 09-17 07:22 至今（09-20 14:10），约 3 天 7 小时，这条链一件事都没做成**——
不是卡在难题上，是卡在**连不上模型**。同一个窗口里，工作区那批未编译改动就一直烂在那里。

> 记录卫生小问题：G 编号跳过了 G43；`G51` 的小标题写成 `(2026-04-10)`，与前后文时间线矛盾。
> 不影响结论，但说明这份台账的自动生成环节需要抽检。

---

## 4. 真正完成了的（本轮可复核）

别被第 1 节吓到 —— 已完成的部分是**有证据的**，这是这个项目和普通"AI 改代码"最不一样的地方。

### 4.1 M2「服务端中心边界」确实大幅收敛（实测）

| 指标 | DIRECTION 撰写时 | 本轮实测 | 变化 |
|------|------------------|----------|------|
| `plugins/` 内 `transaction {` | 75 处 / 18 文件 | **15 处 / 4 文件** | ↓ 80% |
| `repository/` + `service/` → `plugins/` 反向依赖 | 9 处 / 5 文件 | **0** | ✅ 归零 |
| `plugins/` 直接 import Exposed | 36 文件 | **22 文件** | ↓ 39% |
| 新增中立包 `server/common/` | — | 7 个文件 | 新建 |

剩余 15 处集中在 4 个文件：`DeveloperRouting`(4)、`AnnouncementRouting`(4)、`AdminDiagnosticsRouting`(4)、`AdminBulkRouting`(3)。
`ServerArchitectureTest` 的**精确相等**棘轮基线（15/4）与实测**逐项一致**，说明基线没有失真。

### 4.2 M1「可执行契约」在位

- 服务端 `ServerArchitectureTest`：2 条绝对不变量 + 5 条精确相等棘轮；源码根目录找不到就**直接失败**（拒绝在空目录上静默通过）。
- 客户端 `ClientHotspotRatchetTest`：热点行数 + Composable 直连持久层 87 处/17 文件 + 整个 `ui/` 的 ports 预算。
- 两者基线本轮均实测一致。这正是 DIRECTION 第 2 节想要的"把散文变成会失败的东西"。

### 4.3 M3「E2EE 不变量有证据」基本闭环

`docs/messaging-v2-architecture.md` 的不变量条目中，**`→ 缺口：` 已归零**（全仓 grep 只剩 1 处散文里的"缺口"字样），
每条都挂着 `→ 验证：<Class>#<用例名>`，并由 `MessagingInvariantTraceabilityTest` 做门禁
（引用的用例必须真实存在，缺口数按棘轮只减不增）。

覆盖层次已经从"边界"推到了"真加解密往返"：真 X3DH 双向棘轮、生产 store 跨重启存活、
群 SenderKey 真往返、解密畸形输入矩阵、真客户端↔真服务端的消息分支矩阵、第二设备、离线补投、
Sender Key repair 闭环、附件路径、终态删除不变量。

### 4.4 台账完成度

410 个受控条目：**[x] 247 / [~] 53 / [ ] 110**。

- 严格完成（[x]）：**60%**
- 含"已有新基础但旧路径仍在"（[x]+[~]）：**73%**

按域看，消息与聊天核心（M01–M11）、聊天 UI（U01–U07）、Server 多数领域（B03/B05/B06/B07/B10/B11/B12）
已是 100% 的 [x]；薄弱区集中在下面第 5 节。

### 4.5 工程规模

| 指标 | 值 |
|------|-----|
| 已跟踪文件 | 1610 |
| 提交总数 | 907 |
| `app/src/test` 测试文件 | 299 |
| `server/src/test` 测试文件 | 111 |
| `app/src/androidTest` 仪器测试 | **11**（DIRECTION 撰写时只有 4） |
| CI job | 4 个：`server` / `android` / `instrumented` / `docker-config` |
| 客户端热点 | `ChatDetailRoute.kt` 5048 / `ChatDetailViewModel.kt` 3131 / `GroupPlayPolicy.kt` 2298 |

---

## 5. 结构性缺口（完成数 = 0 的高价值区）

这些节**一项都没勾**，且绝大多数连 `[~]` 都没有——说明还没真正开始：

| 节 | 完成 | 缺口实质 |
|----|------|----------|
| **Q04** 双账号双设备离线 E2E | **0 / 6** | 无「每账号两设备」矩阵；无 ACK 前后杀进程、网络切换、重复事件；无真实音视频/ICE 重连 |
| **Q03** Compose 与系统集成 | **0 / 3** | **没有任何 Compose UI 测试**（11 个仪器测试全在数据层）；无截图回归（浅/深色、平板、横屏、大字体、RTL、中英） |
| **Q05** 性能、可靠性与安全 | **0 / 4** | 无 10 万消息本地库基准；无 PG 并发/retention/双节点 WS/滚动发布；无 SSRF/path traversal/敏感日志检查；无 SBOM/依赖漏洞/密钥扫描 |
| **Q02** 数据库与迁移 | **0 / 4** | PG 迁移矩阵已有切片，但「最后生产版本真实旧库 fixture」仍用「前 3 个迁移」模拟；SQLCipher 密钥/磁盘满/损坏故障注入为空 |
| **P10** 性能、无障碍、i18n、UI 稳定性 | **0 / 5** | 全空 |
| **4.1 / 4.2** 目标模块拆分 | **0 / 20** | 模块边界的目标态一条没落 |
| **Q01** 单元与架构测试 | 0 / 13（10 项 [~]） | 大面靠棘轮守住了，但"每个 command/query 的成功/失败/取消/重复/账号切换"未覆盖；协议 fuzz/兼容测试为空 |
| **Q06** CI 与发版 | 0 / 6（3 项 [~]） | 缺生产签名 Secret 缺失必须失败、SBOM/可复现构建、真机验收；生产机上的 backup→rollback 演练仍未做 |

另外两条**来自台账自身记录、本轮未复核但值得记下**的运营风险：

1. 生产构建 `main` 落后 —— `/health/ready` 的 checks **缺 `migrations` / `backgroundTasks`**（G6 只读探针实测）。
2. 生产主机 sshd **只广播 `password`、未启用公钥**，导致 G6 的部署访问无法使用、真实生产演练至今没做。

---

## 6. 建议的下一步（按优先级，不含时间估计）

### P0 — 先把主干从红的变成绿的（今天）

这批改动有价值（M2 的收敛是真的），但**不能以"未编译"的形态挂在工作区**。三选一，别拖：

1. **修**：补 `AdminManagementRepository` 的缺失 `}`、把 `GroupAttachmentCommitRecords`/`Chats.avatar`/
   `RiskEvents` 四个虚构列改成真实 schema、把 4 处调用点重指到 `server/common/`。
   预计是**局部修改**，不是重写。
2. **切**：若短期内修不动，把这批改动 `git stash` 掉，让 `main` 回到 `8d76cec6` 的绿状态，
   再按单个 G 目标小步重做。
3. **不要**：继续在上面加新功能。

无论哪条，**先跑一次 `server:test` 全量**，拿到真实基线。

### P1 — 让自主链不再"静默停摆 3 天"

本次停摆的根因是模型路由 403，但**后果**是没人发现。低成本护栏：
在 `keepgoal` 链上加一条「连续 N 次 `all-routes-failed` 就告警/落一条显式记录到 `DIRECTION.md`」，
让"链死了"本身成为一个可见事实，而不是要等人工来翻 `ledger.jsonl`。

### P2 — 回到 DIRECTION 的轨道 C：客户端热点

`ChatDetailRoute.kt` 5048 行里那个"`while(true)` 每 60s 写库"是这个项目最贵的债，
且 `ClientHotspotRatchetTest` 已经把它钉住了。M2 主体已经收口（反向依赖归零 + 事务 75→15），
按 DIRECTION 自己的排序，现在轮到它了。

### P3 — 补两块"门禁覆盖不到"的硬缺口

- **Q03 Compose 测试 + 截图回归**：目前整个 UI 层零自动化，改 UI 完全靠人眼。
- **Q05 安全扫描 + SBOM**：一个宣称 E2EE 的产品，没有密钥扫描和依赖漏洞扫描，是不匹配的。

---

## 附：本轮所有结论的复现命令

```bash
# 1. App 主源码
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:compileDebugKotlin --console=plain          # → BUILD SUCCESSFUL

# 2. App 单测（红）
./gradlew :app:testDebugUnitTest --console=plain           # → RealtimeEventPolicyTest.kt:159-164

# 3. Server 主源码（红）
cd server && ../gradlew compileTestKotlin --console=plain  # → :compileKotlin FAILED, 5 处

# 4. M2 边界实测
grep -rE "transaction[[:space:]]*\{" server/src/main/kotlin/com/maodouchat/server/plugins/ | wc -l   # 15
grep -rlE "transaction[[:space:]]*\{" server/src/main/kotlin/com/maodouchat/server/plugins/          # 4 文件
grep -rn "import com.maodouchat.server.plugins" server/src/main/kotlin/com/maodouchat/server/repository/ \
                                                 server/src/main/kotlin/com/maodouchat/server/service/ | wc -l  # 0

# 5. 编出来的表名 / 列名
grep -rn "GroupAttachmentCommitRecords" server/src                        # 只在自己文件里
grep -n "groupAvatar" server/src/main/kotlin/com/maodouchat/server/db/CoreTables.kt:55
sed -n '538,556p' server/src/main/kotlin/com/maodouchat/server/db/CoreTables.kt   # RiskEvents 真实列

# 6. 链条停摆
python3 -c "import json;rows=[json.loads(l) for l in open('.keepgoal/ledger.jsonl') if l.strip()];\
print([d['at'] for d in rows if d['kind']=='goal-ended'][-1]);\
print(len([d for d in rows if d['kind']=='all-routes-failed']))"

# 7. 台账完成度
python3 -c "import re;t=open('docs/full-project-refactor-checklist.md',encoding='utf-8').read();\
print({k:t.count('- ['+k+']') for k in 'x~ '})"
```

**反面复核（本轮主动排查并否掉的一个错判）**：初次用 `grep "transaction\s*{"` 得到 0 处，
一度以为 M2 已 100% 完成。实际是 BSD grep 对 `\s` 的处理差异导致漏报。改用
`grep -rE "transaction[[:space:]]*\{"` 后得到真实的 15 处。**口径错误会同时制造乐观和悲观两种假结论，
所以本报告的每个数字都附了可复现命令。**

---

## 7. 修复记录（同日执行）

审计完成后，按 P0「先把主干从红的变成绿的」执行了修复。**修复期间没有新增任何产品功能。**

### 7.1 结果

| 检查 | 修复前 | 修复后 |
|------|--------|--------|
| `:app:compileDebugKotlin` | ✅ | ✅ |
| `./gradlew :app:testDebugUnitTest` | ❌ 编译失败 | ✅ **1531 例全绿** |
| `../gradlew compileTestKotlin`（server） | ❌ 131 个错误 | ✅ |
| `../gradlew test`（server） | 未到达 | ✅ **440 例全绿** |
| `../gradlew postgresIntegrationTest` | 未到达 | ✅ **18 例全绿**（真实 PostgreSQL 16.15） |
| `ServerArchitectureTest` 棘轮 | — | ✅ 15 处/4 文件、Exposed 22 文件，与基线精确一致 |

> 注：`postgresIntegrationTest` 需要 PostgreSQL。本轮在 `/tmp/maodou-pg-test` 起了一个**临时实例**
> （端口 55432，跑完即停），未使用用户已有的任何数据库。

### 7.2 服务端编译的 131 个错误，根因只有三类

**A 类 · 工具下移到中立包只做了一半**（`common/` 包）
- `common/ServerCommons.kt` 是**残留草稿**：重复声明 `AttachmentConstants` 与 `WebhookSecurityUtils`，
  直接产生 4 个 Redeclaration 错误 → **删除**。
- `common/WebhookSecurityUtils.kt` 缺 `postPinnedWebhookJson`（`BotWebhookService` 已在引用）→
  从 `plugins/RoutingHelpers.kt` 把整簇固定-IP webhook IO（约 356 行：`postPinnedWebhookJson`、
  `isAllowedWebhookAddress`、`openPinnedWebhookSocket`、`readPinnedWebhookResponse` 及其私有依赖）
  **逐字**搬过来；`plugins/RoutingHelpers.kt` 侧删除，`DeveloperRouting` / `MinimalRouteTest`
  的引用改指 `common`。**已用 diff 验证搬迁前后零逻辑改动**——这是安全敏感代码（防 DNS rebinding），
  不允许顺手"改好"。
- `common/CallSignalingValidators.kt` **不是忠实迁移**：信令类型被写成 `CALL_OFFER`/`CALL_ANSWER`/
  `CALL_ICE` 并要求 JSON `sdp` 结构，而真实契约是小写 `offer`/`answer`/`ice-candidate`、
  且**不解析 SDP**；`callId` 上限被从 **100** 改成 **64**。**按原样上线会让每一通真实通话都被判成非法。**
  已替换为对 `plugins/Validation.kt` 的忠实副本，并删除 plugin 侧重复定义（5 个常量 + 3 个函数），
  使 `common` 成为唯一事实源。

**B 类 · 一个文件被追加到了函数体内部**（`AdminManagementRepository.kt`）
- HEAD 版本 151 行、括号平衡、干净。工作区版本 386 行、`{`84 / `}`83 **差一个**，
  错误信息为 `Syntax error: Missing '}'`。
- 而且**新增的 235 行是照想象中的 API 写的**：引用了全仓不存在的表 `GroupAttachmentCommitRecords`、
  不存在的列 `Chats.avatar`（真名 `Chats.groupAvatar`）、不存在的 `Chats.status`；
  `ChatAdminResponse` 的字段名（`type`/`name`/`ownerId`/`lastActiveAt`/`isEncrypted`）
  与真实模型（`isGroup`/`chatType`/`groupName`/`groupAnnouncement`/`memberCount`/`lastActivity`）
  完全对不上；`RiskEvents` 与 `RiskEventAdminResponse` 连 import 都没有。
- **修复方式**：以 HEAD 版本为基线重建该文件，把 4 个路由 handler 里的内联 SQL
  **逐字搬入**（那些 SQL 是被 CI 验证过的、正确的），方法名与签名对齐路由的真实调用点。
  `systemOverviewStats` 按 HEAD 内联实现补回（`AdminSystemRouting` 在用它），
  并删除其中下游从未使用的 `activeSessions` 死代码。

**C 类 · 虚构的列名**（`UserTagRepository.kt`）
- 新增的"打标 + 风控事件同事务"逻辑往 `RiskEvents` 写了 `eventType`/`severity`/`actionTaken`/`details`
  四个**不存在的列**。真实列只有
  `id/userId/sourceValue("source")/ruleId/action/matched/referenceId/needsReview/createdAt`。
- 修复：改用真实列——事件类型进 `sourceValue`、动作进 `action`、风险级别与标签名编码进 `matched`、
  标签 id 进 `referenceId`，并保留 `needsReview=true` 与本轮新增的串行化语义。

### 7.3 测试侧：6 个"假绿"与 2 个空断言

**这是本轮最值得记的发现。** 4 个未跟踪的测试文件从未编译过，但台账已把它们记成 `[x]` 的证据。
逐个修的过程里发现，它们的断言方式本身就在制造**假绿**：

| 测试 | 问题 | 处理 |
|------|------|------|
| `WebSocketContractTest` | 装配用了**不存在的 `configureSecurity`**；两个用例是 `assertTrue(true)` 与「对自己硬编码的本地列表做断言」（列表里的 `TYPING` 服务端根本不存在） | 装配修正；空断言删除；下游枚举改为**源码扫描棘轮**（真实 14 个类型精确冻结）；上游白名单用例实测真的通过 |
| `RealtimeEventPolicyTest`（客户端） | 白名单与真实 `WebSocketEvent` 密封子类**完全对不上**，长期为红；且 `assertTrue(条件, 消息)` 用了与所导入 JUnit4 相反的参数顺序 | 改为真实 16 个子类精确冻结 + **非空守卫**（原写法若枚举返回空集会静默通过） |
| `AttachmentLimitsAndDefenseTest` | 5 个用例的请求形状系统性错误（注册缺 `name`、建会话用 `targetUserId`、会话体用 `sha256`/`totalChunks`、直传用 `X-Sha256`、会话 id 读 `uploadId`）。**最危险的是**：请求因参数不合法被 400 拒掉，而断言恰好也在期望 400 → 会绿，却什么都没证明 | 全部按真实契约重写；每个用例刻意让**被断言的那条规则成为唯一被违反的规则**；新增一个"合法 cipherSize 必须 201"的对照用例 |
| `PostgresPlaintextSweepIntegrationTest` | 同类问题（V2 发送打到不存在的 `/api/messaging/v2/send`、admin session 用邮箱密码当 body、期望列名写成不存在的 `encrypted_payload`） | 按仓库里已通过的 `/api/v2/messages` 用例的装配方式重写 |
| `MinimalRouteTest` 里的 `FakeAiGateway` | 是 `private`，其他测试无法复用 → 逼出重复替身 | 提升为 `internal` |

**诚实标注的证明边界**（写进代码注释，不藏起来）：
- 「直传 >100 MiB 上界」**没有**被 HTTP 层证明——Ktor 测试客户端会用真实 body 长度覆盖显式
  `Content-Length`，无法用小 body 触发该分支。上界改由**分块会话**用例覆盖（`cipherSize` 走 JSON），
  那条是可信的；直传侧改为钉住"声明长度与实际送达不符必须拒收"这条真实生效的防线。
- 类型名扫描只能证明"没有名字像正文投递的下游类型"，说不出 payload 里的内容——
  后者由载荷级扫描（`ServerPlaintextSweepTest` / `OutboxPlaintextBoundaryTest` / PG 扫描）负责。

### 7.4 台账更正

在 `docs/full-project-refactor-checklist.md` 里就地更正了 4 处**没有命令输出支撑的 `[x]`**：
- **不变量「WebSocket 只承载唤醒…」** 的 G41 证据段：两个被引用的用例名**在代码里不存在** →
  换成真实用例名与真实断言内容。
- **G41 节**：加更正块，并指出**结构性根因**——`docs/messaging-v2-architecture.md` 有
  `MessagingInvariantTraceabilityTest` 守着"引用的用例必须真实存在"，**而本清单没有这层保护**，
  所以它能长期承载不存在的证据。**建议给清单也加一道同类门禁。**
- **G40 节**：「`postgresIntegrationTest` 套件 5 类全面通过」当时不成立（文件未编译）→
  换成本轮实测的 18 例全绿与真实证据描述。
- **G44 节**：5 条用例描述与实际不符 → 换成更正后的 6 例实际覆盖，并显式写出未覆盖的配额 507 路径。
- **G45 节**：结论成立但落地质量不达标（见 7.2 A 类）→ 逐条记录。

### 7.5 仍未做 / 建议下一步

1. **提交**：本轮所有修改仍未提交（`git status` 里现在是 31 改 + 3 删 + 6 新增）。
   建议按主题拆成几个提交（webhook/信令下移、AdminManagementRepository 重建、
   附件与 PG 测试修正、台账更正），便于回滚。
2. **仓库根目录的调试残留**（未跟踪，建议清理，本轮**未擅自删除**）：
   `run_compile.sh`、`test_runner.py`、`test_env.txt`、`test_compile_out.txt`、`test_runner_out.txt`
   —— 都是上一个会话为绕开编译失败留下的临时脚手架，现已无用。
3. **给清单加引用门禁**：仿 `MessagingInvariantTraceabilityTest`，校验清单里
   `` `Class#用例名` `` 形式的引用在测试源码里真实存在。这是本次"假证据"问题的**根治手段**。
4. **CI**：`postgresIntegrationTest` 已在 CI 的 `server` job 里；本轮本地已验证全绿。
   仍需一次真实 CI 运行确认（本机无法替代）。
5. 原审计报告第 5 节的结构性缺口（Q03 Compose/截图、Q04 双设备 E2E、Q05 性能与安全、P10）**一条未动**。

