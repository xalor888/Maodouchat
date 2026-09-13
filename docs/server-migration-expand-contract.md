# Server 数据库 Expand → Contract 发布流程

B01 要求破坏性 schema 变更不得在同一发布里「先删再建」。本仓库用版本化 `MigrationRunner`（`schema_migrations` + PG advisory lock / H2 行锁）落地下面四步。

## 阶段

1. **Expand**  
   只加不删：新表、新列（可空或带默认值）、新索引、并行写路径。  
   例：migration v1 的 `createSchemaTables()` / `createMissingTablesAndColumns`；v4 给信令表加 `epoch`/`seq_no`/`idempotency_key`。

2. **Compatibility**  
   新旧代码同时可读写。旧列/旧表仍保留；新代码写双写或只写新结构并回填读路径。  
   发布顺序：先部署能理解新旧形态的服务，再跑数据迁移。

3. **Backfill**  
   在线或受控批处理把历史行填到新结构。  
   例：migration v3 `backfillDirectChatPairs`；v5 `backfillSignalKeyDeviceIds` / `backfillSignalDeviceConfirmation` / `backfillMissingSignalDevices`。

4. **Contract**  
   删除旧列/旧表/旧兼容分支，且仅在明确 contract 版本执行。  
   例：migration v2 `retireLegacyMessagingTables`（含人类明文守卫，拒绝在仍有遗留人体消息时 drop）。

## 生产启动

`Application` 在 Exposed 连接后**只**调用 `runDatabaseMigrations()`。  
表结构的 expand 入口是 migration v1（`applyBaselineSchemaMigration` → `createSchemaTables`），不再在启动期单独 `initDatabase()`。

`initDatabase()` 仍保留给 **测试夹具**：在不记录 migration 版本的情况下快速 `createMissingTablesAndColumns`，便于仓储/路由单测。需要验证版本链时测试应显式调用 `runDatabaseMigrations()`。

## 规则

- 版本号严格递增、不可变；失败事务回滚且不写入 `schema_migrations`。
- 破坏性 drop 只能出现在 contract 版本，并带数据守卫（见 `LegacyMessagingRetirement`）。
- 双实例下 migration 与周期清理任务分别用 advisory/行锁与 `job_leases`，避免并发改 schema 或双跑清理。
- 新领域表优先进入下一个 expand migration，而不是「启动期隐式建表、免版本号」的长期旁路。
