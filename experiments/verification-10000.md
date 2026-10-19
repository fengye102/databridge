# 1 万行校验预实验（2026-10-19）

这是一轮开发期冒烟测量，不代替选题方案要求的每组至少三次正式实验。

| 项目 | 值 |
|---|---:|
| 源库 | Testcontainers MySQL 8.4.11 |
| 目标库 | Testcontainers PostgreSQL 17.11 |
| JVM | 本机 JDK 23.0.2，代码按 Java 21 编译 |
| 表结构 | `id BIGINT PRIMARY KEY, name VARCHAR(100)` |
| 行数 | 两端各 10,000 行，内容相同 |
| 扫描批次 | 500 行，按主键 keyset 读取 |
| 结果 | `CONSISTENT`，差异 0 行 |
| 包含双探针的耗时 | 2,377 ms |
| 端到端吞吐 | 约 4,207 行/秒（按源端行数除以上述耗时） |
| JVM 堆池峰值合计 | 92,759,264 字节（约 88.5 MiB） |

复现命令：在 `D:\code\Program\毕设\backend` 执行 `mvn --batch-mode -q clean verify -Pintegration`；`VerifierIT` 会输出 `VERIFICATION_10000_ROWS_MS`、`BATCH_SIZE`、`PEAK_HEAP_BYTES`。峰值在校验前对 JVM 堆池计数器清零后读取，仍包含同一进程其他线程与框架占用，不能解释为校验器独占内存。吞吐包含两次 CDC 探针等待，因此不是纯扫描吞吐。

同一测试还在扫描前探针写入后对源表追加一行，验证结果为 `INCONCLUSIVE/SOURCE_CHANGED`。正式性能章节仍需固定容器资源、运行三次以上、记录原始 CSV 和环境噪声。
