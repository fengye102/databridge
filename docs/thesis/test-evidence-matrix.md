# DataBridge 需求—证据对照（2026-10-20）

本文正文第 6 章的功能性结论只应引用下列已执行测试或已记录的本机演示；功能测试不替代独立的性能/延迟实验。后端完整回归命令为 `mvn --batch-mode -q clean verify -Pintegration`（52 项发现、50 项通过、两个独立基准有意跳过）；前端为 `npm test`（9 项通过）与 `npm run build`。全量基准另有 18 条逐次数据，CDC 延迟基准另有 300 条逐次数据、分位数和 ECDF 图。

| 需求或风险 | 自动化证据 | 可得结论及边界 |
|---|---|---|
| 真实 Binlog 增删改与重启捕获 | `CdcCaptureIT.capturesOrderedDmlAndResumesAfterRestart`、`CdcReadinessIT.initialAndRestartedCaptureBecomeReadyBeforeCopying` | 临时 MySQL 的事件捕获与探针就绪成立；不是公网或多实例测试 |
| 事件去重、持久入库、失败回滚 | `CdcEventRepositoryIT` 两个用例、`EventKeyTest` 三个用例 | 重发键、唯一约束与批次回滚由测试覆盖 |
| 全量批次与检查点 | `FullCopyWorkerIT.copiesSignedSparseKeysWithoutExtendingHighWatermark`、`rollsBackEntireBatchAndRetriesFromUnchangedCheckpoint`、`failedLaterBatchKeepsEarlierCommittedCheckpoint` | 固定上界、稀疏键和事务性检查点在真实双库成立 |
| 全量期间源端变化后最终状态 | `EndToEndSyncIT.copyThenReplayOverwritesOldValuesAndPreservesDeletes` | 测试场景覆盖插入、更新、删除的衔接，不代表任意并发时序的形式化证明 |
| 回放顺序、应用游标和失败重试 | `EventApplierIT.replaysInSequenceWithIdempotentCursorAndDelete`、`failedSecondEventRollsBackRowsAndCursorThenRetries` | 目标行与游标同事务，失败批次可重试 |
| 类型边界与错误拒绝 | `FullCopyWorkerIT` 类型拒绝用例、`EventApplierIT.preservesBoundaryValuesAndRejectsMalformedRow`、`TaskPreflightIT` 结构拒绝用例 | 当前限定类型的成功/失败路径有证据，不能称支持任意异构类型 |
| 任务控制与恢复 | `TaskCoordinatorIT.pausesCaptureContinuesAndRestartResumesFromDurableState`、`TaskRecoveryTest` 两个用例 | 单进程状态机和持久恢复有证据；没有跨进程 fencing |
| 校验正确、检出三类差异、竞争时不误报 | `VerifierIT.reportsQuietConsistencyAndPersistsOnlyDifferenceMetadata`、`scansTenThousandRowsAndRejectsAWriteInsideTheProbeWindow`、`reportsInconclusiveWhenProbeCannotBeCapturedOrApplied` | 静默窗口的确定性报告及 `INCONCLUSIVE` 路径成立；不是在线一致性保证 |
| 本机 HTTP 和凭据保护 | `TaskApiIT.bootMigratesMetadataAndHttpControlsDurableTaskWithoutExposingSecrets`、`TaskPreflightIT.failureReasonDoesNotPersistCredentialText` | 接口状态码与净化响应有测试；默认无登录认证，仅允许 loopback |
| 管理界面交互 | `frontend/tests/` 的 9 项测试；浏览器本机三行演示与桌面/手机截图 | 页面可操作，375/768/1440px 无横向溢出；演示并非大规模性能证据 |

## 人工验证与未闭合项

本机演示额外观察到：源端一行更新经 CDC 到目标端可见；两次校验均为三行一致；强制终止后端 Java 进程后重新运行，唯一持久任务恢复到 `STREAMING`，历史报告仍可读。此人工演示与自动化用例互相补充，但缺少自动计时和三次重复，不能代替第 6 章正式故障恢复实验。

全量通道三规模、两批次、各三次的正式数据见 [全量实验报告](../../experiments/full-copy-report.md)；CDC 延迟三轮各 100 条的正式数据见 [延迟实验报告](../../experiments/cdc-latency-report.md)。尚须完成全量写入、事件入库、事件回放三个故障窗口的恢复时间与停写后差异行数重复测量。正式报告应链接原始逐次数据与脚本，绝不以本对照表中的功能测试充数。
