# Full Copy Experiment Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 交付隔离开发库、可重跑的全量通道 3 规模 × 2 批次 × 3 重复实验与原始/汇总结果。

**Architecture:** 专门的 JUnit/Testcontainers 实验类只在显式系统属性下运行；PowerShell 包装脚本固定输出目录，统计脚本从原始 CSV 派生可提交汇总。正式性能与普通集成测试分开。

**Tech Stack:** Java 21、JUnit 5、Testcontainers、MySQL 8.4.11、PostgreSQL 17.11、PowerShell。

**Spec:** `docs/superpowers/specs/2026-10-20-databridge-full-copy-experiment-design.md`。

## File Map

| 文件 | 职责 |
|---|---|
| `backend/src/test/java/com/databridge/copy/FullCopyBenchmarkIT.java` | 生成独立双库实验、固定随机运行顺序、测量、核对结果、逐行 CSV |
| `experiments/run-full-copy.ps1` | 检查本机依赖、调用显式基准测试、生成唯一 raw CSV 路径 |
| `experiments/summarize-full-copy.ps1` | 校验 18 条与每组三次，生成汇总 CSV |
| `experiments/full-copy-runs.csv` | 核验完成后提交的 18 条逐次测量，保留论文可核查证据 |
| `experiments/full-copy-summary.csv` | 真实运行后的统计结果，不预填 |
| `experiments/render-full-copy.py`、`experiments/figures/` | 从汇总 CSV 生成可复现的论文矢量图 |
| `experiments/requirements.txt` | 图表生成所需的固定 Python 依赖 |
| `experiments/full-copy-report.md` | 环境、命令、统计口径、限制和结果解释 |

## Task 1：实验测量器

- [x] 基准类内置种子规模、每次目标行数与完成检查点断言；普通 Maven 集成测试对基准 1 项有意跳过，显式开启时输出逐次 CSV。
- [x] 用 100 行 smoke 和 1 万行 pilot 验证受控容器、种子、随机顺序、计时和 CSV 后，执行正式 18 次运行。

## Task 2：运行与统计

- [x] 编写 PowerShell 运行/统计脚本，先用人工小 CSV 验证缺组/重复组失败，再完成 18 次运行。
- [x] 完整 raw CSV 核验后提交小体量逐次测量，生成汇总、图表与报告；不把技术重复描述为跨机独立样本。

## Task 3：回归与交付

- [x] 运行完整后端回归（50 项通过、基准 1 项有意跳过）、脚本检查与 `git diff --check`，补齐 README。
- [x] 提交并推送独立分支，创建以控制台 PR #7 为 base 的堆叠 PR #8。
