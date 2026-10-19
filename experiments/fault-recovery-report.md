# 三故障窗口强制终止与恢复实验（2026-10-20）

## 指标与环境

在独立的 MySQL 8.4.11、PostgreSQL 17.11 Testcontainers（各 2 CPU / 2 GiB）中运行真实 `TaskCoordinator` 和 Debezium。父 JUnit 进程持有数据库容器，负责观察持久状态；业务任务运行在单独的 `-Xmx512m` 子 JVM。达到窗口条件后调用 `Process.destroyForcibly()` 并确认非零退出码，再启动新的子 JVM恢复同一任务。一次性数据库与本机 Compose 开发库隔离。宿主为 Windows 11、Docker Desktop WSL2、JDK 23.0.2，后端按 Java 21 编译。

`recovery_ms` 用父进程单调时钟从**调用重启子进程**开始，直到任务重新为 `STREAMING`、源和目标全行相同且本轮业务事件游标已追平为止；包含 JVM 启动、Debezium 就绪、剩余全量/事件应用与检测轮询，不是纯数据库事务恢复时间。源端注入的业务更新在重启计时前已完成，其后停写。每试次独立表和任务；三次重复共享同一对容器及宿主，属于技术重复。

## 注入窗口与证据

| 窗口 | 强杀前可核查条件 | 数据集 |
|---|---|---:|
| 全量写入 | `status=COPYING`，持久 `full_checkpoint.last_id=10` 且未完成 | 1000 行，批次 10 |
| CDC 事件入库 | `status=STREAMING`；锁住 `event_cursor`；本轮业务事件已提交到 `cdc_event` 且序号大于游标 | 20 行，更新第 1 行 |
| 事件回放 | `status=STREAMING`；锁住目标业务行；事件已入库、游标未推进，`pg_blocking_pids` 显示应用进程正在等待所持行锁 | 20 行，更新第 1 行 |

第二个窗口**不直接观测** Debezium 内部 offset 确认瞬间；第三个窗口证明应用事务正在等待目标行锁，但不代表所有 SQL 指令都已执行。全量窗口在首批检查点之后，可能恰处于两个批次之间，不声称进程恰在未提交的 SQL 语句中终止。目标行锁由父进程在强杀后解除。

## 复现与数据

```powershell
& 'D:\code\Program\毕设\experiments\run-fault-recovery.ps1' -Smoke
& 'D:\code\Program\毕设\experiments\run-fault-recovery.ps1'
& 'D:\code\Program\毕设\experiments\summarize-fault-recovery.ps1' -InputCsv '<正式 CSV 绝对路径>'
```

独立基准默认在普通后端回归中跳过，只有显式系统属性才执行。一次冒烟（每场景 1 次）与正式实验（每场景 3 次）均通过。正式测试 1 项、0 失败/错误/跳过，测试历时 143.307 秒。原始正式文件为 `experiments/raw/fault-recovery-formal-20261019T171449Z.csv`；完整 9 行逐字节复制到受版本控制的 [`fault-recovery-runs.csv`](fault-recovery-runs.csv)，两者 SHA-256 均为 `74332B42E77C02AC6DFBCA91E1F578B43A1D2B10BE812385ACF3B0F0673A6FF2`。汇总仅从逐次 CSV 生成，脚本拒绝缺场景、缺重复、重复试次、负耗时、非零差异和缺少窗口证据。子进程诊断日志位于忽略的 `experiments/raw/`；这些日志不含开发库密码。

## 结果

| 窗口 | 三次恢复时间（秒） | 中位恢复时间（秒） | 三次最终差异行数 |
|---|---|---:|---|
| 全量写入 | 14.420、14.599、14.276 | 14.420 | 0、0、0 |
| CDC 事件入库 | 4.346、4.401、4.218 | 4.346 | 0、0、0 |
| 事件回放 | 4.231、4.250、4.226 | 4.231 | 0、0、0 |

精确到毫秒的数值、任务 ID、杀前检查点/事件/游标/阻塞连接数和源/目标行数见[逐次 CSV](fault-recovery-runs.csv)及[汇总 CSV](fault-recovery-summary.csv)。9 次均确认子 JVM 被强杀、重启后进入 `STREAMING`，静止源与目标的全行值一致。全量窗口在仅完成 10/1000 行后中断，恢复需要继续复制 990 行；其耗时不能与另外两个只需应用一条更新的场景直接比较。

## 有效性限制

三个故障窗口是特意构造的可观察点，不覆盖任意时刻掉电、数据库自身宕机、Binlog 被清理、多进程同时运行或长期抖动。全量恢复测的是 1000 行小批次，不代表百万行任务的恢复时间；CDC/回放各只有一条业务更新。每次重启都在同一对仍健康的容器上进行，实验不提供跨机器置信区间或生产 SLA。全行比较是在源端停写后进行，不证明在线跨库强一致；重复事件能否出现及最终去重仍由事件唯一键与集成测试共同约束。
