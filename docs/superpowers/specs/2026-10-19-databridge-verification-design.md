# DataBridge 静默窗口校验与报告设计

> 依据已确认的核心链路设计第 8 节；本文件细化校验边界，2026-10-19。

## 目标与非目标

校验一个已进入 `STREAMING` 的 MySQL→PostgreSQL 单表任务。在同一 CDC Engine 中分别写入开始和结束 `sync_probe` 标记，并等待事件应用游标越过各自标记；如果两个标记之间出现该业务表的变更事件，本次结果为 `INCONCLUSIVE`，不宣称在线一致。校验成功时报告 `CONSISTENT` 或 `DIFFERENT`。不支持多进程校验、自动修复、在线强一致或与源端写事务跨库锁定。

## 顺序与正确性

1. 读取任务状态，只允许 `STREAMING`；校验同一任务时只允许一个运行中的校验器。
2. 向源库 `sync_probe` 写入唯一开始标记，等待本任务 `cdc_event` 中有该标记，且 `event_cursor.last_sequence` 大于等于其序列号；等待时任务停止、失败或超时均结束为 `INCONCLUSIVE`。
3. 在源连接执行 `SET time_zone = '+00:00'`。源/目标按有符号 `id` 升序分批读取，只保存当前批次；按列名比较同名字段，处理 NULL、整数、DECIMAL 尾零、DATETIME/TIMESTAMP、Unicode NFC、JSON 键顺序和数值规范化。对每行规范化内容计算 SHA-256；哈希不同时逐列定位，缺行/多行按 id 报告。
4. 写入唯一结束标记，等待该标记事件应用。如果开始与结束标记的序列号之间存在本业务表的 CDC 事件，结果一律 `INCONCLUSIVE`，扫描期间的比较计数不用于宣称差异。操作员仍应停止源端写入以获得可重复结果。
5. 结果及最多 100 条差异样本在一个 PostgreSQL 事务内写入 `verification_run`/`verification_diff`。样本仅含行 id、差异类型和不一致列名，不落库字段原值或凭据。比较数据的读取不在跨库事务中，静默窗口是结果有效性的必要条件。

## 接口与存储

- `POST /api/tasks/{taskId}/verifications`：同步执行并返回 201 和报告。首版适用于演示规模；长时间运行接口未来可改异步作业，不改变存储格式。
- `GET /api/tasks/{taskId}/verifications/{runId}`：读取报告与前 100 条差异。任务与报告不匹配返回 404。
- `verification_run`：`run_id`、`task_id`、`status`、`source_rows`、`target_rows`、`difference_rows`、`reason_code`、`started_at`、`completed_at`；状态 `CONSISTENT/DIFFERENT/INCONCLUSIVE`。
- `verification_diff`：`run_id`、`row_id`、`kind` (`MISSING_TARGET/EXTRA_TARGET/VALUE_MISMATCH`)、`columns` (JSONB 字符串数组)；不保存原始行。
- 保留报告，迁移用仅追加的 Flyway `V5__verification_report.sql`；不改 V1–V4。

## 验收

真实 MySQL/PostgreSQL 测试覆盖零差异、目标漏行/多行/列值不同、DECIMAL/时间/JSON/Unicode 等价表示、源端扫描窗口内写入导致 `INCONCLUSIVE`、暂停或停止拒绝校验、无原始业务值及密码入报告。测试规模至少 1 万行，并记录耗时、吞吐、峰值内存和扫描批次大小，供论文实验复现。
