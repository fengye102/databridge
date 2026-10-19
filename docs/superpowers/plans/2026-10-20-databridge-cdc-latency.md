# CDC Latency Experiment Implementation Plan

**Goal:** 以真实双库/真实 Debezium 采集和应用通道测量 3 轮 × 100 条固定节拍更新的可观察延迟，并交付逐次数据、分位数与解释。

**Architecture:** 独立 `CdcLatencyBenchmarkIT` 只在显式系统属性下运行；同一 JVM 单调时钟驱动源提交与目标轮询，正式 CSV 经 PowerShell 校验/汇总后提交。与全量实验的种子、容器和指标分离。

**Spec:** `docs/superpowers/specs/2026-10-20-databridge-cdc-latency-design.md`

| 文件 | 职责 |
|---|---|
| `backend/src/test/java/com/databridge/cdc/CdcLatencyBenchmarkIT.java` | 临时双库、任务启动、固定节拍写入与目标轮询、逐次 CSV |
| `experiments/run-cdc-latency.ps1` | 显式启动基准测试，生成唯一临时输出路径和日志 |
| `experiments/summarize-cdc-latency.ps1` | 严格校验三轮各 100 条，计算最近秩 P50/P95/P99 |
| `experiments/cdc-latency-runs.csv` | 已核验的 300 条逐次数据 |
| `experiments/cdc-latency-summary.csv`、`cdc-latency-report.md` | 统计结果、环境和限制 |
| `experiments/render-cdc-latency.py`、`experiments/figures/cdc-latency-ecdf.svg/.png` | 从逐次数据重建三轮经验累积分布图 |

- [x] 先实现数据完整性断言与汇总脚本负面用例：少一条、重复序号、负延迟必须失败。
- [x] 在明确系统属性下运行 5 条小型冒烟，再运行 3 × 100 条正式实验；每轮目标全部追平，任何任务失败即中止。
- [x] 保留小体量逐次数据、分位数结果、ECDF 图、环境与方法说明；更新论文第 6 章，不把轮询指标称为纯 CDC 内部延迟。
- [ ] 完整后端/前端回归、脚本检查和提交，创建以全量实验 PR #8 为 base 的堆叠 PR。
