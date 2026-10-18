# DataBridge 核心链路设计

> 状态：2026-10-19 用户确认开始按本设计实施；分阶段验收，不视为所有功能已完成。
> 对应范围：`docs/proposals/databridge-proposal-v3.md`。
> 设计日期：2026-10-18。

## 1. 要解决的问题

在 MySQL 源表持续增删改时，完成 PostgreSQL 目标表的全量迁移，并在全量结束后继续同步。进程可能在任意数据库操作前后崩溃；恢复后允许事件重放，但源端停写且增量追平时，目标表必须与源表一致。

不试图提供跨库强一致、全场景 Exactly Once 或通用数据库适配。第一阶段只有一张源表对应一张目标表、一个任务对应一个独立的 Debezium Engine；同一目标表不允许两个任务并发写入。

## 2. 设计取舍

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 全量和 CDC 同时直接写目标 | 写入路径短 | 旧全量批次可能覆盖已应用的新事件，删除也会被复活 | 不采用 |
| 先全量，CDC 只依赖源端 Binlog 暂存，结束后再启动捕获 | 组件少 | 长时间全量受 Binlog 保留期约束；初始位点与恢复难验证 | 不采用 |
| CDC 先进入 PostgreSQL 持久事件日志，全量结束后按序回放 | 顺序和恢复可用数据库事务验证 | 额外占用磁盘，需要积压监控 | 采用 |

只使用单线程的 CDC 事件入库和单线程的事件应用。多表并发、事件并行分区均留到核心正确性测试通过后再讨论。

## 3. 运行前约束

1. 源库 MySQL 开启 Binlog，`binlog_format=ROW`、`binlog_row_image=FULL`，并为最长全量时间、可能停机时间与恢复时间留出足够保留期。[Debezium MySQL 前置条件](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
2. 源表有唯一且不可变的单列有符号 BIGINT 主键。任务建立时校验主键、字段类型和目标表结构；目标表必须为空且主键或唯一约束足以支持 UPSERT。
3. 同步期间不执行 DDL，不更改源表主键。运行时检测到无法解释的 schema 事件或主键变更时置为 `FAILED`，不跳过并推进位点。
4. 首版时间映射固定为 MySQL `DATETIME` 无时区墙上时间、MySQL `TIMESTAMP` 源连接 UTC 会话后的 UTC 墙上时间，均写入 PostgreSQL `timestamp without time zone`；微秒精度保持。任务配置记录源端与目标端的实际时区设置。首版支持 BIGINT、INTEGER、VARCHAR/TEXT、DECIMAL、DATETIME、TIMESTAMP、JSON 和 NULL；不支持的类型在建任务时拒绝。
5. 目标业务表的触发器、外键副作用不在首版保证范围。开发与演示环境由 Compose 固定数据库版本。
6. 数据源连接串与密码从环境变量注入，任务只保存数据源配置标识，不保存明文密码；界面只选择预配置数据源。

## 4. 组件责任

- `TaskCoordinator`：唯一任务状态机，校验、启动、暂停、恢复、停止和失败处理。状态为 `CREATED → CAPTURING → COPYING → REPLAYING → STREAMING`；`PAUSED` 保留原阶段，`FAILED` 保留错误原因和可恢复信息。
- `CdcCapture`：配置一个 MySQL Debezium Engine，首次启动用 `snapshot.mode=no_data`；使用 PostgreSQL 的持久化 offset 与 schema-history 存储；将业务源表的行变更按接收顺序写入事件日志。集成测试额外捕获专用 `sync_probe` 表。`schema_only` 已弃用。[Debezium MySQL 快照模式](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- `FullCopyWorker`：从已固定的高水位以下按主键读取，转换后批量 UPSERT；数据和检查点同事务提交。它不读取事件日志，也不在 `REPLAYING` 或 `STREAMING` 状态运行。
- `EventApplier`：按事件日志序列号应用 INSERT/UPDATE 的最终行值和 DELETE；业务写入与应用游标同事务提交。它不在 `COPYING` 状态运行。
- `RowMapper`：对 JDBC 行与 Debezium 行变更产生相同的目标列值，单元测试用同一组类型边界样本验证。
- `Verifier`：源端停写并确认捕获、应用均追平后，按主键范围读取两端，在 Java 中规范化并比较行哈希；差异块进一步逐行对比。
- `TaskApi`：只暴露任务创建、状态查看、启动、暂停、恢复、停止、校验和报告读取；前端仅调用这些接口。

## 5. 持久数据与事务边界

元数据位于目标 PostgreSQL 的 `databridge_meta` schema；业务表位于独立 schema。

| 表 | 关键字段 | 事务要求 |
|---|---|---|
| `sync_task` | `task_id`、源/目标表名、状态、错误、创建时间 | 状态转移单独提交；不可代替数据检查点 |
| `full_checkpoint` | `task_id`、`high_watermark`、`last_id`、完成标志 | `last_id` 与对应全量批次 UPSERT 同事务 |
| `cdc_event` | `task_id`、递增 `sequence`、唯一 `event_key`、操作、主键、规范化后的行值、源 offset | 事件入库提交后才调用 Debezium `markProcessed`；重复 `event_key` 不产生第二次应用 |
| `event_cursor` | `task_id`、最后已应用的 `sequence` | 游标与对应事件的业务写入同事务 |
| `verification_run`、`verification_diff` | 观察时间、比较范围、差异摘要与样本 | 仅记录报告，不改业务数据 |

`event_key` 由 Debezium 的源分区、完整源 offset、表名、操作类型和主键确定性序列化后计算 SHA-256。实现时用重复投递、同一事务多行、主键变更事件三类样本验证是否唯一；若样本无法保证唯一性，不进入全量开发。事件按**入库序列号**应用，不按时间戳排序；捕获端每任务只有一个串行 `ChangeConsumer`。Debezium 的 `markProcessed`/`markBatchFinished` 用于确认消费，offset 仍可能延后刷盘，因此设计明确允许崩溃重放。[Debezium Engine 高级消费与故障行为](https://debezium.io/documentation/reference/development/engine.html)

首版不清理 `cdc_event`，保留去重依据。事件日志大小、未应用数量和最旧事件时间作为监控指标；达到配置的磁盘阈值时任务停止并报警，而不是丢弃事件。

## 6. 启动、全量与衔接时序

1. 创建任务时检查 MySQL/PG 连接、表结构、源主键、目标空表、目标唯一约束和无同表运行任务。
2. 启动 `CdcCapture`，等待 Debezium 初始 `no_data` schema 快照 `COMPLETED`（恢复时可能 `SKIPPED`）通知。真实双库测试证明新任务此时 JDBC offset 表仍为空，故不能立刻复制。每任务唯一 `topic.prefix` 的 Engine 同时捕获业务表和源库预置 `sync_probe(id BIGINT AUTO_INCREMENT PRIMARY KEY, marker CHAR(36))`；收到快照通知后，用只对该表额外有 INSERT 权限的 CDC 账号写入 UUID 标记，等待本任务的 JDBC offset 持久化，之后才能进入 `COPYING`。探针事件同样入库，应用时只推进游标。超时、`ABORTED`、缺表/权限或 Engine 错误均失败；不以固定休眠或其他任务的 offset 代替。[Debezium 3.7 快照通知](https://debezium.io/documentation/reference/3.7/configuration/notification.html)
3. 在 CDC 就绪后查询 `MAX(id)`，写入 `full_checkpoint.high_watermark`；空表以完成全量处理。此后插入的行即使主键落在高水位以下，也由 CDC 回放覆盖。
4. 全量每批执行 `WHERE id > :last_id AND id <= :high_watermark ORDER BY id LIMIT :batch_size`。以**本批最后实际读取的主键**推进检查点；空结果即完成。数据写入与检查点更新在一个 PostgreSQL 事务。
5. 全量完成标志持久化后进入 `REPLAYING`。按 `sequence > event_cursor.sequence ORDER BY sequence` 读取事件，逐批应用；每批业务写入与游标同事务提交。未知类型、损坏事件或目标写入失败时暂停应用并记录 `FAILED`，游标不越过失败事件。
6. 日志积压降至零后进入 `STREAMING`；捕获端继续把新事件写入日志，应用端持续从游标读取。全量写入器不再启动。

只把行值看作全量读取时的一次观察，不假设全量期间存在统一快照。由于 CDC 起点早于任何全量读取，且所有后续事件在全量完成后按源端顺序应用，源端停止写入并追平后，事件的最后状态覆盖全量中的旧状态。该论证依赖完整事件、顺序、稳定主键和固定 schema；任何前提不满足则不宣称正确。

## 7. 暂停、停止和恢复

- `PAUSED`：暂停全量写入器或事件应用器，CDC 捕获继续进入日志；显示积压量。恢复时回到原阶段。积压达到容量上限即失败，不默默丢事件。
- `STOPPED`：用户显式停止时，先停止写入器/应用器，再优雅关闭 Engine；保留所有检查点、事件及 Debezium 状态。再次恢复必须先验证所需 Binlog 尚在，缺失时失败而不是从最新位置继续。
- 异常进程退出：启动时读取持久任务阶段，重启 CDC；`COPYING` 从 `last_id` 继续，`REPLAYING/STREAMING` 从 `event_cursor` 继续。若全量批次已提交而检查点未提交不可能出现；两者事务一致。
- 重复 CDC 事件：唯一 `event_key` 使其不再次入队。PG 目标写入成功但游标未提交时整个事件应用事务回滚；已提交则恢复后不会再次应用。

## 8. 校验与验收

确定性校验步骤：停止测试写入；在测试环境向被同一 Debezium Engine 捕获的专用 `sync_probe` 表写入标记事件，等待该标记被事件应用器消费，以证明此前业务事件已走完捕获和应用链路；随后比较源、目标业务表。`sync_probe` 事件只推进游标，不写目标业务表。应用层按相同主键范围读取两端，规范化 NULL、DECIMAL 精度、时间语义、Unicode 和 JSON，再计算行哈希并定位不一致行。生产界面只能把无屏障的在线结果标为“观察值”，不得标为一致性证明。

必须通过的自动化测试：

1. 单表 1 万行全量后零差异；目标初始非空、缺主键、类型不兼容均被拒绝。
2. 全量读取旧值后，源端更新、CDC 先入日志、全量后写，回放后目标为新值；对应删除场景目标行不存在。
3. 在全量数据与检查点事务前后强杀进程，恢复后零差异。
4. 在 CDC 事件入库提交前后强杀进程，恢复后没有缺失事件；重复 event key 只应用一次。
5. 在事件应用事务前后强杀进程，游标与目标行状态一致。
6. 插入、更新、删除、空值、Unicode、DECIMAL、时间和 JSON 边界样本在全量与 CDC 两路径得到相同目标值。
7. 人为造成漏行、多行和字段差异，校验报告准确定位。

## 9. 非目标与后续实施门槛

多表、复合主键、无锁自动建表、在线校验、跨库事务、EventApplier 并发化和“同事务 Debezium 内部 offset”均不在核心设计内。先完成真实 CDC 最小验证并固定依赖版本，再编写全量通道；未验证事件唯一性、顺序与恢复前，不开始前端或性能优化。
