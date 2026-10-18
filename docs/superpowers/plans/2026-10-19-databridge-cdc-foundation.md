# DataBridge CDC Foundation Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 用真实 MySQL Binlog 事件证明单表 CDC 能按源端顺序持久化到 PostgreSQL，并在重复投递与进程重启后不漏、不重入队。

**Architecture:** Spring Boot 只提供进程容器和 JDBC/Flyway；一个串行 Debezium Engine 捕获单表，先把每条业务事件写入 PostgreSQL 事件日志，事务提交后才调用 Debezium committer。Debezium offset 和 schema history 使用同一 PostgreSQL 的独立 JDBC 表；业务目标表与全量通道暂不实现。集成测试使用临时 MySQL/PostgreSQL 容器，不修改现有开发库。

**Tech Stack:** Java 23 本机运行、Java 21 编译目标、Spring Boot 3.5.16、Debezium 3.7.0.Final、Maven 3.9.16、MySQL 8.4.11、PostgreSQL 17.11、JUnit 5、Testcontainers。

**Spec:** `docs/superpowers/specs/2026-10-18-databridge-core-design.md`

## Global Constraints

- 单表、单任务、单个串行 `ChangeConsumer`；固定 BIGINT 主键；源表结构在运行中不变。
- 事件入库事务提交后才能 `markProcessed`；批次结束后调用 `markBatchFinished`。
- 唯一键由源分区、完整 offset、表名、操作、主键的规范序列化计算 SHA-256；不能靠目标行状态去重。
- 同一事件批次按接收顺序入库；`sequence` 是未来回放的唯一排序依据。
- 密码只来自环境变量或集成测试容器动态配置；构建日志不得输出密码。
- 测试先失败再实现；默认 `mvn test` 只跑单元测试，`mvn verify -Pintegration` 跑临时双库集成测试。

## File Map

| 文件 | 单一职责 |
|---|---|
| `backend/pom.xml` | 版本锁定、依赖、Surefire/Failsafe |
| `backend/src/main/java/com/databridge/DataBridgeApplication.java` | Spring Boot 入口；不在启动时自动创建同步任务 |
| `backend/src/main/resources/db/migration/V1__cdc_event.sql` | `databridge_meta` schema 与事件日志表 |
| `backend/src/main/java/com/databridge/cdc/CdcEvent.java` | 规范化行变更值对象 |
| `backend/src/main/java/com/databridge/cdc/EventKey.java` | 确定性事件身份计算 |
| `backend/src/main/java/com/databridge/cdc/CdcRecordMapper.java` | Kafka Connect SourceRecord → CdcEvent |
| `backend/src/main/java/com/databridge/cdc/CdcEventRepository.java` | 事务化插入、去重与查询 |
| `backend/src/main/java/com/databridge/cdc/CdcCapture.java` | Debezium Engine 生命周期、串行批次消费、提交后确认 |
| `backend/src/test/java/com/databridge/cdc/EventKeyTest.java` | 映射顺序无关、offset 行位置不同、不同行/操作不同 |
| `backend/src/test/java/com/databridge/cdc/SchemaMigrationIT.java` | 空 PostgreSQL 初始化与约束检查 |
| `backend/src/test/java/com/databridge/cdc/CdcRecordMapperTest.java` | 三种行变更与未知操作处理 |
| `backend/src/test/java/com/databridge/cdc/CdcEventRepositoryIT.java` | PostgreSQL 唯一约束、顺序与事务回滚 |
| `backend/src/test/java/com/databridge/cdc/CdcCaptureIT.java` | 真实 MySQL DML、事件顺序、重启和重复投递 |

---

### Task 1: 构建骨架与事件表

- [ ] 先创建可执行测试的最小 `pom.xml`，锁定上述版本及 JUnit/Testcontainers/Flyway 依赖；运行 `mvn test`，确认工程基线可构建。
- [ ] 写 `SchemaMigrationIT`：空 PostgreSQL 执行 Flyway 后存在 `databridge_meta.cdc_event`，`(task_id,event_key)` 唯一，`sequence` 单调递增；运行测试，观察缺少迁移脚本而失败。
- [ ] 创建 Maven 工程、Boot 入口和 `V1__cdc_event.sql`；事件列为 `sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`、`task_id UUID NOT NULL`、`event_key CHAR(64) NOT NULL`、`source_partition JSONB NOT NULL`、`source_offset JSONB NOT NULL`、`table_name TEXT NOT NULL`、`operation CHAR(1) NOT NULL`、`row_id BIGINT NOT NULL`、`after_row JSONB`、`captured_at TIMESTAMPTZ NOT NULL DEFAULT now()`。
- [ ] 跑 `mvn test` 和单项 IT；确认 migration 可重复执行且错误 schema 不会静默通过。

### Task 2: 事件身份、映射和入库

- [ ] 先写 `EventKeyTest`：分区/offset 键顺序改变时哈希相同；同一事务多行的 `row` offset 或主键不同则不同；操作不同则不同。观察失败。
- [ ] 实现 `EventKey.sha256(Map<String, ?> partition, Map<String, ?> offset, String tableName, String operation, long rowId)`，递归排序对象键并序列化后 SHA-256；输入缺失时抛明确异常。
- [ ] 先写 `CdcRecordMapperTest`：INSERT/UPDATE/DELETE 的主键、操作、after_row 与 offset 正确；不接受缺少主键或未知操作；运行观察失败，然后实现 mapper。
- [ ] 先写 `CdcEventRepositoryIT`：重复 event_key 只留一行、不同事件按 sequence 排列、事务内第二条失败时第一条回滚；运行观察失败。
- [ ] 实现 `CdcEventRepository.insertBatch(UUID taskId, List<CdcEvent> events)`，用单个 PostgreSQL 事务和 `ON CONFLICT (task_id,event_key) DO NOTHING`；运行单元与 IT 直到通过。

### Task 3: 真实 Debezium 捕获

- [ ] 先写 `CdcCaptureIT`：临时 MySQL 开 ROW/FULL Binlog、建 BIGINT 主键表；临时 PG 执行迁移；启动 no_data Engine 后插入、更新、删除，在事件日志中按序等到三条；重启 Engine 后继续写入并确认只新增事件；运行观察失败。
- [ ] 实现 `CdcCapture`：固定 `snapshot.mode=no_data`、MySQL 单任务连接、PostgreSQL JDBC offset/schema-history、`record.processing.threads=1`、`tombstones.on.delete=false`；只接收配置的业务表；每批先提交事件，再逐条 `markProcessed` 和 `markBatchFinished`。
- [ ] 扩展 IT：同一事务多行、手动重复入库、事件入库失败时不确认、Engine 关闭重启后无丢失；逐项观察先失败再通过。
- [ ] 跑 `mvn clean verify -Pintegration`，记录实际版本、测试数量和失败点；更新 README 的运行边界，明确尚未实现全量/回放/API/前端。

## Exit Gate

只有 Task 3 的真实集成测试通过，才能开始 FullCopyWorker。若事件身份无法区分同事务多行，或 JDBC offset/schema-history 与事件日志的恢复测试失败，先修正设计与本计划，不以模拟测试替代真实链路。
