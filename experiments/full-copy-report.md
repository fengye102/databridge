# 全量通道性能实验记录

## 问题与口径

比较 `FullCopyWorker` 在 1 万、10 万、100 万行和 500/1000 行批次下的复制耗时、吞吐及 JVM 堆峰值。本实验**只**测从第一次 `copyNextBatch` 到空批完成并核对检查点的全量通道；不含 Testcontainers 启动、数据生成、CDC 捕获/探针、任务 API、校验扫描或图表生成。源表在测量期间无写入，故不能用这里的吞吐替代“在线迁移端到端吞吐”。

## 可复现环境

| 参数 | 固定值 |
|---|---|
| 宿主 | Windows 11、Docker Desktop WSL2 后端，Docker Engine 29.8.2 |
| Docker Desktop 可见资源 | 16 CPU、约 7.6 GiB 内存（`docker info`） |
| 临时源库 | MySQL 8.4.11；2 CPU、2 GiB 容器上限 |
| 临时目标库 | PostgreSQL 17.11；2 CPU、2 GiB 容器上限 |
| JVM | JDK 23.0.2 运行，Java 21 编译目标；`JAVA_TOOL_OPTIONS=-Xmx512m` |
| 图表运行环境 | Python 3.14.6、matplotlib 3.11.2（`experiments/requirements.txt`） |
| 表 | `id BIGINT PRIMARY KEY`、`name VARCHAR(64)`、`amount DECIMAL(12,2)`；目标 `amount NUMERIC(12,2)` |
| 因子 | 3 种行数 × 2 种批次 × 3 次技术重复，种子 `20261020` 打乱次序 |
| 预热 | 1 万行/500 批次一次，不进入正式 CSV |

三次重复共享同一对容器和宿主缓存，是技术重复而非不同机器、不同日期的独立样本。每次使用新任务 UUID，清空同一目标实验表，运行后断言目标行数、检查点完成及最后主键正确。任何失败使实验中止，不写入成功记录。容器与开发环境的 `infra/.env` 隔离，不触碰已有演示业务表。

实验启动后用 `docker inspect` 核对两只临时数据库容器的 `HostConfig.Memory=2147483648` 和 `HostConfig.NanoCpus=2000000000`；同机开发用 Compose 容器未设置该限制，未用于测量。

## 执行与计算

在 PowerShell 中从仓库根目录运行：

```powershell
& 'D:\code\Program\毕设\experiments\run-full-copy.ps1'
& 'D:\code\Program\毕设\experiments\summarize-full-copy.ps1' -InputCsv '<上一步输出的 CSV 绝对路径>'
python -m pip install -r 'D:\code\Program\毕设\experiments\requirements.txt'
python 'D:\code\Program\毕设\experiments\render-full-copy.py'
```

第一条命令将逐次数据写入不跟踪的 `experiments/raw/full-copy-formal-<UTC>.csv`，运行日志写入忽略的 `work/`。本次完整测量从原始文件逐字节复制为受版本控制的 `experiments/full-copy-runs.csv`，SHA-256 均为 `C2958DB1187E6D87DDAB83B725FE0BAEF90889679D8886210E83D135132187E4`。汇总脚本要求恰好 18 条记录，且每组重复号为 1、2、3；不完整或重复输入会失败。汇总结果为 `experiments/full-copy-summary.csv`，图由该汇总文件重建为 `experiments/figures/full-copy-median-time.svg/.png` 和 `full-copy-throughput.svg/.png`。`rows_per_second` 由本次行数除以纳秒墙钟耗时；堆峰值是重置后的 JVM 各堆池最高使用量合计，包含测试框架和驱动活动，不能解释为复制器独占内存。

## 结果与解释

正式 18 次运行已完成；JUnit 基准测试 1 项通过，0 失败/错误/跳过，执行约 2055 秒。完整逐次数据在 `full-copy-runs.csv`，每组统计在 `full-copy-summary.csv`。下表使用逐次记录计算的中位数和均值，不以单次最好结果代替整个组。

新增基准类对普通回归默认禁用；随后执行的完整 `mvn --batch-mode -q clean verify -Pintegration` 结果为 51 项发现、50 项通过、0 失败/错误、1 项基准有意跳过。

| 源端行数 | 批次 | 中位耗时（秒） | 耗时范围（秒） | 平均吞吐（行/秒） | 三次中最大 JVM 堆池峰值（MiB） |
|---:|---:|---:|---:|---:|---:|
| 10,000 | 500 | 3.650 | 3.635–3.674 | 2,737.13 | 65.32 |
| 10,000 | 1,000 | 2.828 | 2.789–2.893 | 3,525.16 | 65.52 |
| 100,000 | 500 | 34.932 | 34.619–34.954 | 2,870.68 | 65.81 |
| 100,000 | 1,000 | 26.759 | 26.489–26.895 | 3,743.44 | 65.66 |
| 1,000,000 | 500 | 344.829 | 344.459–345.644 | 2,898.74 | 66.38 |
| 1,000,000 | 1,000 | 264.094 | 263.641–264.495 | 3,786.78 | 65.76 |

在这个固定环境与表结构下，批次 1000 相比 500 的中位耗时分别降低约 22.52%、23.40%、23.41%。行数增加十倍时耗时接近十倍；较大规模的吞吐比 1 万行更稳定，符合固定启动/收尾开销占比下降的解释，但并未单独测出各子环节，因此这只是解释而非已验证的瓶颈定位。更大的批次可能减少数据库往返和事务次数，但若扩展到宽行、JSON 或不同磁盘配置，需要重新实验。六组最大堆池峰值约 65–66 MiB，未随行数同比增长；该指标受测试 JVM 其他活动影响，不能单独证明严格内存上界。

![三规模、两批次的全量复制中位耗时](figures/full-copy-median-time.png)

![三规模、两批次的全量复制平均吞吐](figures/full-copy-throughput.png)

## 有效性威胁

宿主上有 Docker Desktop 与其他低负载开发服务，磁盘缓存及 JIT 预热不能完全消除；运行顺序随机化可缓解但不能消除时间漂移。所有组在一对容器上运行，不具备跨机器独立重复。MySQL 源表无并发写入，结果不反映 CDC 积压、Binlog I/O 或应用回放成本。`FullCopyWorker` 当前每个批次重新检查结构并建立数据库连接，这属于被测实现的一部分，不应从耗时中人为扣除。
