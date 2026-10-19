# DataBridge Verification Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 对已追平的单表任务执行双探针静默窗口比较，持久化无敏感行值的一致性报告。

**Architecture:** `VerificationBarrier` 通过源库探针和 PostgreSQL 事件/游标证明扫描前后已追平；`Verifier` 用有界 keyset 批次合并比较两库行；`VerificationRepository` 将最终结论和最多 100 条差异样本原子保存；HTTP 层只暴露触发和报告读取。

**Tech Stack:** Java 21、Spring Boot 3.5.16 JDBC/Web、Jackson、Flyway、JUnit 5、Testcontainers MySQL 8.4.11/PostgreSQL 17.11。

**Spec:** `docs/superpowers/specs/2026-10-19-databridge-verification-design.md`；`Codex.md`。

## Global Constraints

- 仅 `STREAMING` 任务可校验；源端停写且两个探针之间无业务 CDC 事件才可给出确定性结论。
- 元数据只保存主键、差异列名与计数；不保存字段值、密码或 JDBC URL。
- 按 `id` 有界扫描；数值、时间、Unicode 和 JSON 在 Java 中比较逻辑值，不比较驱动输出文本。
- 异常或期间写入报告 `INCONCLUSIVE`，不得误报零差异；真实双库测试先红后绿。

---

## File Map

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V5__verification_report.sql` | 报告与差异样本表 |
| `backend/src/main/java/com/databridge/verify/VerificationBarrier.java` | 前后探针、捕获及应用游标等待、业务写入检测 |
| `backend/src/main/java/com/databridge/verify/RowCanonicalizer.java` | 跨驱动类型规范化与行指纹 |
| `backend/src/main/java/com/databridge/verify/Verifier.java` | 有界扫描、合并比较和结论生成 |
| `backend/src/main/java/com/databridge/verify/VerificationRepository.java` | 原子存取报告与最多 100 个差异样本 |
| `backend/src/main/java/com/databridge/verify/VerificationController.java` | 本机 HTTP 触发与报告读取 |
| `backend/src/main/java/com/databridge/config/RuntimeConfiguration.java` | 校验器及仓储注入 |
| `backend/src/test/java/com/databridge/verify/RowCanonicalizerTest.java` | 类型与哈希边界 |
| `backend/src/test/java/com/databridge/verify/VerifierIT.java` | 真实双库零差异、差异及静默窗口检测 |
| `README.md`、`Codex.md` | 操作说明、阶段状态 |

### Task 1: 报告持久化

- [x] 写失败的 `VerifierIT`：运行一张无差异表后应存在 `CONSISTENT` 报告；人为漏行、多行、改值后应返回 `DIFFERENT` 且只存差异元数据。
- [x] 执行 `mvn --batch-mode -q '-Dit.test=VerifierIT' verify -Pintegration`，确认缺少 V5/报告类导致失败。
- [x] 创建 V5、`VerificationRepository` 的 `save(VerificationResult)` 和 `get(UUID taskId, UUID runId)`；一个事务插入 run 与差异样本，限制 100 条。
- [x] 专项测试通过。

### Task 2: 双探针与有界比较

- [x] 写测试：探针事件未应用不可进入扫描；前后探针间的业务事件将结论变为 `INCONCLUSIVE`；`RowCanonicalizerTest` 比较 DECIMAL 尾零、JSON 键顺序、Unicode NFC、时间及 NULL。
- [x] 运行专项测试，确认失败。
- [x] 实现 `VerificationBarrier` 的 `writeAndAwait(UUID, CopyTable, Duration)`、`businessEventBetween(UUID, CopyTable, long, long)`；`Verifier.verify(UUID, Duration, int)` 只允许 `STREAMING`，用 `WHERE id > ? ORDER BY id LIMIT ?` 从两端取有界批次并合并比较。
- [x] 专项测试通过；在 1 万行上记录运行耗时和差异统计。

### Task 3: HTTP 与交付

- [x] 写失败 HTTP 测试：POST 触发、GET 读取，未知报告 404，非 `STREAMING` 状态 409，响应不含字段原值或凭据。
- [x] 运行测试确认缺路由失败；实现 `VerificationController` 和安全错误映射。
- [x] 更新 README、完成 `mvn --batch-mode -q clean verify -Pintegration`、`git diff --check`；提交/推送 `codex/verification`，创建以 `codex/runtime-api` 为 base 的堆叠 PR。

## Exit Gate

本计划不做自动修复、跨进程校验锁、在线一致性承诺或前端页面；前端独立阶段只消费已定义报告接口。

## 验证记录（2026-10-19）

- `mvn --batch-mode -q clean verify -Pintegration`：50 项测试，0 失败、0 错误、0 跳过。
- `git diff --check`：无空白错误。
- 1 万行预实验与限制记录于 `experiments/verification-10000.md`。
