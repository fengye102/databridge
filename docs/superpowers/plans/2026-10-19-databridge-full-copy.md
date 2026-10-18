# DataBridge Full Copy Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在 CDC 已就绪的前提下，以固定主键高水位完成 MySQL→PostgreSQL 单表全量复制，并让每批业务写入和断点处于同一事务。

**Architecture:** `FullCopyWorker` 只处理全量，不读取或应用 CDC 日志。首次调用查询源表 `MAX(id)` 并在目标库持久化高水位；后续从持久检查点按 `id` 做 keyset 分页。每批使用一个 PostgreSQL 事务完成 UPSERT 和 `last_id` 更新；失败后重新调用可从原检查点继续。

**Tech Stack:** Java 21 编译目标、Spring Boot 3.5.16 JDBC、Flyway、MySQL 8.4.11、PostgreSQL 17.11、JUnit 5、Testcontainers。

**Spec:** `docs/superpowers/specs/2026-10-18-databridge-core-design.md` 第 3、4、5、6、7、8 节。

## Global Constraints

- 仅单表、单列不可变有符号 BIGINT `id` 主键；目标表预先创建且初始为空。
- 调用方必须先确认 CDC 已捕获 Binlog；本组件不启动 CDC，也不将 CDC 事件写入目标。
- 全量开始时持久化固定的 `high_watermark`；`last_id` 可为 NULL，不能把 `Long.MIN_VALUE` 当哨兵。
- 每批目标 UPSERT 与 `last_id` 更新共用一个 PostgreSQL 事务；失败整个事务回滚。
- 动态表名和列名只接受简单 SQL 标识符并逐段引用；不拼接未验证输入。
- 首版仅接受设计列出的 BIGINT、INTEGER、VARCHAR/TEXT、DECIMAL、DATETIME、TIMESTAMP、JSON 和 NULL；未知类型应报错。
- 测试先失败后实现；`mvn --batch-mode -q clean verify -Pintegration` 是完整验证命令。

---

## File Map

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V2__full_checkpoint.sql` | 全量高水位、最后已提交主键、完成标志 |
| `backend/src/main/java/com/databridge/copy/CopyTable.java` | 源/目标表及 SQL 标识符校验 |
| `backend/src/main/java/com/databridge/copy/FullCopyWorker.java` | 初始化检查点、读取源批次、目标 UPSERT 与事务提交 |
| `backend/src/test/java/com/databridge/copy/FullCopyWorkerIT.java` | 真实双库复制、边界行、回滚和恢复 |
| `backend/src/test/java/com/databridge/copy/CopyTableTest.java` | 标识符验证和引用 |

### Task 1: 检查点 schema 与表名边界

- [x] **Step 1: Write failing tests.** `FullCopyWorkerIT` 首先验证 Flyway 后存在 `databridge_meta.full_checkpoint`，`task_id` 唯一，`last_id` 可以为 NULL；`CopyTableTest` 验证 MySQL 合法标识符被反引号引用、PostgreSQL 合法标识符被双引号引用，而空值、带点/空格/引号的单段名称被拒绝。
- [x] **Step 2: Run tests and inspect red.** 执行 `mvn --batch-mode -q -Dtest=CopyTableTest test` 和 `mvn --batch-mode -q -Dit.test=FullCopyWorkerIT verify -Pintegration`；失败原因应为类或表缺失，而非 Docker/凭据错误。
- [x] **Step 3: Implement minimum.** V2 表字段为 `task_id UUID PRIMARY KEY`、`high_watermark BIGINT`、`last_id BIGINT`、`completed BOOLEAN NOT NULL DEFAULT FALSE`、`updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`；空源表使用 NULL 高水位和 `completed=TRUE`。`CopyTable` 构造时正则验证四段名称，提供 `sourceQualifiedName()` 与 `targetQualifiedName()`。
- [x] **Step 4: Re-run tests.** 两项测试通过；提交 `feat(copy): add checkpoint schema and table identifiers`。

### Task 2: 固定高水位和可恢复 keyset 批次

- [x] **Step 1: Write failing integration tests.** 使用临时 MySQL/PostgreSQL，先建源/目标表；写入 `Long.MIN_VALUE`、普通和稀疏 BIGINT 主键，启动一次复制后插入大于初始最大值的新行，继续运行并确认新行不在全量范围。断言每批 `last_id` 等于实际最后一行主键，空批才置 `completed`；再次调用已完成任务不重复写入。
- [x] **Step 2: Run red.** `mvn --batch-mode -q -Dit.test=FullCopyWorkerIT verify -Pintegration` 应因 `FullCopyWorker` 缺失失败。
- [x] **Step 3: Implement minimum.** `FullCopyWorker(DataSource source, DataSource target)` 暴露 `copyNextBatch(UUID taskId, CopyTable table, int batchSize)`，返回该次处理行数；首次源端 `SELECT MAX(id)` 后写检查点，之后 `SELECT * ... WHERE (? IS NULL OR id > ?) AND id <= ? ORDER BY id LIMIT ?`。对每个结果按列名写目标 UPSERT；行数据与检查点同 PG 事务。完成后返回 0；参数无效立即报错。
- [x] **Step 4: Re-run green.** 运行该 IT；提交 `feat(copy): resume bounded keyset batches`。

### Task 3: 失败原子性与类型边界

- [x] **Step 1: Write failing integration tests.** 通过目标表触发器使批次第二行失败，确认目标无本批行且 `last_id` 未前进；移除触发器、重新构造 worker 后重试，确认仅提交一次。覆盖 `NULL`、Unicode、DECIMAL、JSON、DATETIME/TIMESTAMP；给目标表不兼容列时应失败且不推进检查点。
- [x] **Step 2: Run red.** 同一 IT 命令应显示预期断言失败。
- [x] **Step 3: Implement minimum.** 对受支持 JDBC 类型作明确映射；JSON 使用 PostgreSQL `jsonb` 参数，`DATETIME` 保留墙上时间，`TIMESTAMP` 将 MySQL 源连接会话设为 UTC 后保留 UTC 墙上时间，二者以 `LocalDateTime` 写入 PostgreSQL `timestamp without time zone`；拒绝未知源 JDBC 类型及不兼容目标列。失败时 rollback 并抛出原错误。
- [x] **Step 4: Re-run all.** `mvn --batch-mode -q clean verify -Pintegration` 全通过，记录测试数与实际限制，提交 `test(copy): prove atomic retry and supported values`。

## Exit Gate

本计划只交付全量通道。只有原子恢复测试通过后，才开始 `EventApplier`；事件回放、状态机、校验和前端是后续计划。任务前置条件（CDC 已就绪、目标为空、结构相容）在未来 `TaskCoordinator` 中做运行前验证，不应由 `FullCopyWorker` 静默替代。

## 验证记录（2026-10-19）

- `CopyTableTest` 先因类缺失而失败；`FullCopyWorkerIT` 先因 worker 缺失而失败，再逐项实现。
- 非 UTC MySQL 会话的 `TIMESTAMP` 测试先得到错误的 `20:30`；源会话显式设置 UTC、使用 `LocalDateTime` 后得到预期的 `12:30`。
- 窄目标 `DECIMAL(5,2)` 测试先暴露无异常静默舍入；加入精度/标度检查后在写入前拒绝。
- 真实 PostgreSQL 触发器在首批与后续批次注入失败，目标行与 `last_id` 同事务回滚；重新构造 worker 后继续完成。固定高水位、最小有符号主键、空表、10,000 行分批复制均通过。
- 完整 `mvn --batch-mode -q clean verify -Pintegration`：22 项测试，0 失败、0 错误、0 跳过。Testcontainers 使用 MySQL 8.4.11、PostgreSQL 17.11；不接触开发数据卷。
