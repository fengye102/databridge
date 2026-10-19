# DataBridge Task Coordinator Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 将已验证的 CDC、全量复制和事件回放组件编排为可创建、启动、暂停、恢复和停止的单表任务，并在创建前拒绝不满足约束的源/目标表。

**Architecture:** 任务元数据与状态写入 PostgreSQL `sync_task`。`TaskPreflight` 从两端 JDBC 元数据检查主键、类型、列名、目标空表及独占目标；一个任务运行器维护单个 `CdcCapture` 和一个串行复制/回放线程。CDC 使用 Debezium 自定义 notification channel、源库 `sync_probe` 探针写入及本任务持久 offset 作为就绪门槛，然后依次进入 `COPYING → REPLAYING → STREAMING`；重启时依据持久检查点和游标恢复。

**Tech Stack:** Java 21、Spring Boot 3.5.16 JDBC、Debezium 3.7.0.Final、Flyway、JUnit 5、Testcontainers MySQL 8.4.11/PostgreSQL 17.11。

**Spec:** `docs/superpowers/specs/2026-10-18-databridge-core-design.md` 第 3–8 节；`Codex.md`。

## Global Constraints

- 首版单机、单任务/目标表、单列不可变有符号 BIGINT `id` 主键；目标表预先创建且为空。
- 数据库凭据不写入 `sync_task`；只记录源/目标表名、阶段、错误和时间。
- `CdcCapture.start()` 返回不代表就绪；未观察到 `Initial Snapshot` 成功通知和可恢复 JDBC 状态时不设高水位。
- 复制和回放分别遵守现有事务边界；暂停时 CDC 继续入日志；停止时关闭 Engine 但保留全部持久状态。
- 恢复不得重新初始化已存在的高水位或游标；失败须保留阶段与原因，不跳过事件。
- TDD：每个功能先写失败测试，再实现并运行真实双库测试。

---

## File Map

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V4__sync_task.sql` | 持久任务状态、表映射和目标表独占约束 |
| `backend/src/main/resources/META-INF/services/io.debezium.pipeline.notification.channels.NotificationChannel` | 注册 Debezium 自定义通知通道 |
| `backend/src/main/java/com/databridge/cdc/CaptureReadinessChannel.java` | 将初始快照通知路由到对应任务的就绪 Future |
| `backend/src/main/java/com/databridge/cdc/CdcCapture.java` | 设置 notification channel、暴露限时 `awaitReady`、关闭清理 |
| `backend/src/main/java/com/databridge/task/TaskPreflight.java` | 双库表结构、数据与任务独占校验 |
| `backend/src/main/java/com/databridge/task/TaskRepository.java` | `sync_task` 创建、状态转移、错误持久化 |
| `backend/src/main/java/com/databridge/task/TaskCoordinator.java` | 单任务生命周期与阶段运行 |
| `backend/src/test/java/com/databridge/cdc/CdcReadinessIT.java` | 首启/重启通知、超时和 offset 就绪验证 |
| `backend/src/test/java/com/databridge/task/TaskPreflightIT.java` | 合法表、非空目标、主键/类型不兼容拒绝 |
| `backend/src/test/java/com/databridge/task/TaskCoordinatorIT.java` | 状态转移、全量+回放、暂停/恢复/停止及故障恢复 |

### Task 1: CDC 就绪屏障

- [x] 写真实双库失败测试：`CdcCapture.awaitReady(Duration)` 在快照完成且 offset 持久后返回；首启与重启均有断言。关闭/失败唤醒等待者仍需专项用例。
- [x] 运行 `mvn --batch-mode -q '-Dit.test=CdcReadinessIT' verify -Pintegration`，确认缺少接口而失败。
- [x] 用 Debezium 3.7 `NotificationChannel` SPI 注册 `databridge-ready` 通道，监听 `Initial Snapshot` 的 `COMPLETED/SKIPPED/ABORTED`；按 `topic.prefix` 精确路由到任务（通知 `connector_name` 实测为该值，不是 Engine 的 `name`）。`CdcCapture` 同时捕获业务表与 `sync_probe`，每次启动/恢复收到快照通知后写新探针，限时等待新探针事件入库及本任务 JDBC offset 存在；超时/错误失败并清理。`EventApplier` 对探针事件仅推进游标。
- [x] 专项测试通过；与 Task 2 合并为当前阶段提交。

### Task 2: 任务持久化与运行前校验

- [x] 写失败 IT：合法同结构空目标可创建；非空目标、缺少/复合/非 BIGINT 主键、无目标主键、窄 DECIMAL/VARCHAR/TIMESTAMP、不支持列及同一目标第二任务均被拒绝；错误不插入任务行。
- [x] 执行 `mvn --batch-mode -q '-Dit.test=TaskPreflightIT' verify -Pintegration`，确认 V4/类缺失导致失败。
- [x] V4 `sync_task` 保存 `task_id`、四段表名、`status`、`resume_status`、`error_message`、`created_at`、`updated_at`，`(target_schema,target_table)` 唯一。`TaskPreflight` 验证主键、精度/长度/时间语义与空表；`TaskRepository.create` 在验证成功后持久化 `CREATED`。状态更新与乐观条件更新留给 Task 3 的状态机实现。
- [x] 专项 IT 通过；2026-10-19 全量验证 `mvn --batch-mode -q clean verify -Pintegration` 共 36 测试通过，准备提交。

### Task 3: 串行任务运行器与恢复

- [x] 写失败 IT：创建/启动经 `CAPTURING → COPYING → REPLAYING → STREAMING`；全量期间 UPDATE/DELETE 后目标正确；暂停只停复制/应用而 CDC 继续入日志；停止保留位点；新 coordinator 实例从持久检查点/游标恢复；CDC/PG 故障进入 `FAILED` 且有原因。另测进程内重复运行器拒绝及持久 `PAUSED` 恢复。
- [x] 执行 `mvn --batch-mode -q '-Dit.test=TaskCoordinatorIT' verify -Pintegration`，确认状态机缺失而失败。
- [x] 实现每任务单线程运行器，先启动并等待 CDC 就绪，再调用已有 FullCopyWorker/EventApplier；有限批次、暂停检测、关闭顺序和失败处理均明确定义。重新启动读取状态/检查点，不用无条件清空目标或重置游标。首版每实例一个运行器、进程内同任务唯一；跨进程 fencing 留待后续。
- [x] 2026-10-19 执行 `mvn --batch-mode -q clean verify -Pintegration`，41 测试、0 failures/errors/skips；提交 `feat(task): orchestrate and resume migration stages`。

## Exit Gate

本计划不实现 HTTP API、前端、共享 RowMapper 或最终 Verifier；状态机可通过 Java API 集成测试启动。对 Debezium notification 是否足以证明持久位点，以 Task 1 真实测试结果为准；若 `no_data` 不产生可靠 offset，先修订设计再实现任务运行器。
