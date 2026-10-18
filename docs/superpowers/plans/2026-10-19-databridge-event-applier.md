# DataBridge Event Applier Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 全量完成后按 `cdc_event.sequence` 顺序将行变更回放至 PostgreSQL，并在同一目标库事务中推进应用游标。

**Architecture:** 每任务单线程调用 `EventApplier.applyNextBatch`。函数检查全量完成标志，锁定任务游标，按序读取下一批事件；`c/u` 以最终行值 UPSERT，`d` 删除目标行。任何事件解析或数据库写入错误都回滚本批业务行和游标，重复调用从原游标重放。

**Tech Stack:** Java 21、Spring Boot 3.5.16 JDBC、Jackson、Flyway、PostgreSQL 17.11、JUnit 5、Testcontainers；真实捕获测试额外使用 MySQL 8.4.11 / Debezium 3.7.0.Final。

**Spec:** `docs/superpowers/specs/2026-10-18-databridge-core-design.md` 第 4、5、6、7、8 节。

## Global Constraints

- 只在 `full_checkpoint.completed=TRUE` 后回放；`COPYING` 期间绝不写目标表。
- 按任务内 `sequence` 升序，不按捕获时间、源 offset 文本或主键排序；sequence 可有间隙。
- 每批目标变更和 `event_cursor.last_sequence` 在一个 PostgreSQL 事务；故障不得越过失败事件。
- `after_row` 必须是完整对象且 `id=row_id`；未知操作、表名、列或类型都失败，不跳过。
- `DATETIME` 无时区墙上时间与 `TIMESTAMP` UTC 语义必须保持全量通道一致；JSON/DECIMAL/NULL 不丢失。
- 集成测试使用临时数据库，不修改开发卷和真实凭据。

---

## File Map

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V3__event_cursor.sql` | 持久化每任务最后已应用 sequence |
| `backend/src/main/java/com/databridge/cdc/EventApplier.java` | 读取有序事件、验证行、目标 DML 与游标事务 |
| `backend/src/test/java/com/databridge/cdc/EventApplierIT.java` | 顺序、重复调用、COPYING 拒绝、故障回滚、真实行值 |
| `backend/src/test/java/com/databridge/cdc/EndToEndSyncIT.java` | 真实 MySQL Binlog→持久事件→全量→回放贯通 |

### Task 1: 游标与状态门槛

- [x] **Step 1: Write failing IT.** Flyway 后 `event_cursor` 存在，默认游标 `0`；未完成 `full_checkpoint` 时调用 `applyNextBatch` 抛错且业务表无变化。
- [x] **Step 2: Run red.** `mvn --batch-mode -q '-Dit.test=EventApplierIT' verify -Pintegration` 因 V3/类缺失失败。
- [x] **Step 3: Implement minimum.** V3 使用 `task_id UUID PRIMARY KEY`、`last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)`、`updated_at`；`EventApplier(DataSource target)` 的 `applyNextBatch(UUID, CopyTable, int)` 先验证参数和已完成标志。
- [x] **Step 4: Run green and commit.** 目标 IT 通过；提交 `feat(cdc): add guarded event cursor`。

### Task 2: 有序应用与原子恢复

- [x] **Step 1: Write failing IT.** 写入同任务 `c/u/d` 事件、另一个任务事件与重复 key；一批后目标等于最终值，游标等于最后应用的 sequence。再次调用返回 0。触发器使第二事件失败时业务表和游标都不前进，移除触发器后重试成功。
- [x] **Step 2: Run red.** IT 应在缺失 DML/游标逻辑处失败。
- [x] **Step 3: Implement minimum.** 事务中 `SELECT ... FOR UPDATE` 锁游标，`WHERE task_id=? AND sequence>? ORDER BY sequence LIMIT ?`；严格校验 `table_name`、`operation`、`after_row`、主键和列集合；逐事件执行 UPSERT/DELETE，最后更新游标。错误回滚。
- [x] **Step 4: Run green and commit.** 目标 IT 通过；提交 `feat(cdc): replay ordered changes atomically`。

### Task 3: 类型与真实 CDC 贯通

- [x] **Step 1: Write failing IT.** 用含 NULL、Unicode、DECIMAL、JSON、DATETIME/TIMESTAMP 的行作为事件，比较与全量目标行值；真实 Debezium 捕获 INSERT/UPDATE/DELETE 后回放并校验最终行状态。缺列、错误来源表/主键、未知目标类型应阻断游标。损坏 JSON 由 `after_row JSONB` 入库约束拒绝。
- [x] **Step 2: Run red.** IT 在目标值或错误断言处失败。
- [x] **Step 3: Implement minimum.** 根据目标列元数据转换 JSON 节点：`numeric→BigDecimal`、`jsonb→JSON 文本`、`timestamp→LocalDateTime`（带 offset 的字符串转 UTC）；NULL 用 SQL NULL。拒绝不完整对象和不支持目标类型。
- [x] **Step 4: Run all and commit.** `mvn --batch-mode -q clean verify -Pintegration` 全通过，记录测试数，提交文档与测试。

## Exit Gate

此模块完成后仍需抽取共享 `RowMapper`、由 `TaskCoordinator` 编排 CDC 就绪、高水位、COPYING/REPLAYING/STREAMING 状态；以及带 probe 屏障的 `Verifier`。当前两条映射路径通过真实双库边界样本对齐，但尚未共用一个转换类；游标本身不证明 CDC 已追平。

## 验证记录（2026-10-19）

- `EventApplierIT` 先因类缺失而编译失败；完成游标迁移与事务实现后通过。
- PostgreSQL 触发器使第二条事件失败时，前一条业务写入和游标都回滚；移除触发器并重新构造 applier 后顺序重放成功。
- 真实 MySQL Binlog→CDC 事件日志→固定高水位全量→事件回放测试通过：全量已读的旧值被 UPDATE 覆盖，DELETE 不复活，低于高水位的新插入保留；DECIMAL、JSON、DATETIME、TIMESTAMP 末态正确。
- `mvn --batch-mode -q clean verify -Pintegration`：29 项测试，0 失败、0 错误、0 跳过。所有双库容器均由 Testcontainers 临时创建与清理。
