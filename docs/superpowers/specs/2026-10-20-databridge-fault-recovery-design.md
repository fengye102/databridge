# DataBridge 三故障窗口正式实验设计

## 问题与边界

验证独立 JVM 被强制终止后，既有持久元数据是否足以在同一 MySQL/PostgreSQL 表上安全恢复。此实验针对单进程、单表、限定字段类型，不证明分布式租约、任意故障点或跨库原子事务。每个窗口做三次不同任务的技术重复，源端在恢复计时期间停写。

## 环境与注入

- 父 JUnit 进程持有一次性 MySQL 8.4.11、PostgreSQL 17.11 Testcontainers，各固定 2 CPU / 2 GiB，先迁移元数据 schema；正式测试由显式系统属性启用，普通回归跳过。
- 每一试次创建独立源表、空目标表和任务。真正执行 `TaskCoordinator` 的是子 JVM，父进程使用 `ProcessHandle.destroyForcibly()`，确认子 JVM 退出后再启动新的子 JVM。子进程日志留在忽略的 `experiments/raw/`；不使用生产开发库。
- **全量写入**：预装 1000 行，批次 10 行；任务为 `COPYING` 且 `full_checkpoint.last_id` 已推进、`completed=false` 时杀进程。该窗口可能在两个批次之间，并不声称一定杀在 SQL 事务内部。
- **CDC 事件入库**：预装 20 行并等待 `STREAMING`；父进程锁住该任务 `event_cursor` 行阻止应用游标推进，源端更新一行。看到 `cdc_event` 中对应事件已提交且游标仍在其前时杀进程，然后解除锁。此窗口验证已持久事件的恢复路径；不直接观测 Debezium 的内存确认瞬间。
- **事件回放**：预装 20 行并等待 `STREAMING`；父进程锁住目标业务行，源端更新该行，等到事件已入库、应用游标未推进且 PostgreSQL `pg_blocking_pids` 显示应用连接正等待所持行锁时杀进程，然后解除锁。这是应用事务已进入目标写入路径但未提交的窗口。

## 观测和验收

- 对每次新子进程启动前记录单调起点；等任务重新达到 `STREAMING`、目标表全行与静止源表一致、事件游标追到本试次最新业务事件后记录终点。`recovery_ms` 包含 JVM 启动和 CDC 就绪，不是单独的数据库回放耗时。
- 逐次 CSV：场景、重复号、任务 ID、UTC、杀前状态和检查点/事件/游标/阻塞证据、`recovery_ms`、源/目标行数、差异行数、最终状态。所有 9 次成功且差异行数为 0 才形成正式成功结论；任何注入条件未满足或恢复超时，应失败并保留原始诊断，不能换成未记录的新试次。
- 三种场景分别报告三次原值及中位数，明示同宿主、同数据库容器中的技术重复；不推导生产 SLA 或置信区间。进程杀后 PostgreSQL 释放锁的时机属于测量条件。

## 文件

- `backend/src/test/java/com/databridge/fault/FaultRecoveryChild.java`：只负责在独立子 JVM 启动持久任务。
- `backend/src/test/java/com/databridge/fault/FaultRecoveryBenchmarkIT.java`：容器、注入、强杀、重启、差异核查和原始 CSV。
- `experiments/run-fault-recovery.ps1`：显式运行与日志路径。
- `experiments/summarize-fault-recovery.ps1`：三窗口各三次的完整性断言与中位数汇总。
- `experiments/fault-recovery-runs.csv`、`fault-recovery-summary.csv`、`fault-recovery-report.md`：核验后的数据和论文可引用边界。
