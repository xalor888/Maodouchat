# 隔离区（quarantine）

本目录存放因阻塞全工程编译而暂时移出 `app/src` 的未跟踪 WIP 文件。
原内容逐字节保留（`.bak` 后缀），恢复时移回原路径即可。

## 2026-09-05：DefaultAgentDomainPorts.kt.bak

- 原路径：`app/src/main/java/com/maodouchat/ai/agent/ports/DefaultAgentDomainPorts.kt`
- 状态：未跟踪（`??`）、无任何引用方、包路径已过期
  （引用 `com.maodouchat.ai.prompt.*`、`com.maodouchat.data.TokenManager`、
  `com.maodouchat.data.privacy.*`、`com.maodouchat.data.local.AiTaskEntity` 等不存在的符号，
  而正确位置为 `com.maodouchat.ai.*`、`com.maodouchat.network.TokenManager`、
  `com.maodouchat.security.*`、`com.maodouchat.data.local.entity.*`）。
- 影响：该文件导致 `:app:compileDebugKotlin` 全量失败（数十个 unresolved reference），
  阻塞一切单元测试与后续重构验证。
- 决策：移出源码树以恢复基线编译；P06（AI 领域拆分）认领时，
  以 `AgentDomainPorts.kt` 接口 + `AgentToolHost.kt` 现有用线为准重写实现，
  不要直接恢复本备份（包路径与实体字段均已漂移）。

## 2026-09-06：MurexideMessageBubble / MurexideMessageInput / RoomScheduledMessageStore

- 三文件为未跟踪 WIP，经全仓引用审计确认零引用方：
  - 气泡渲染实际走 `TextMessageBubble` / `MediaMessageBubbles`（U02/U03 文档中具名的 Murexide 文件已无人使用，文档声明与代码现状不符，待校准）；
  - 定时存储实际直连 `ScheduledMessageDao`（M09），`RoomScheduledMessageStore` 从未被接线。
- 已移出源码树（加 `.bak` 后缀），保留字节备查；恢复前必须先接线并补测试。
