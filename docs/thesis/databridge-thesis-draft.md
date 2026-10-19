# 基于 Spring Boot 的异构数据库迁移与增量同步平台的设计与实现

> 正文技术草稿，2026-10-20。本文只写本仓库已实现、已验证的事实；全量性能、CDC 延迟和三个故障窗口重复实验已完成。可审阅的 `databridge-thesis-content-draft.docx` 由本文件生成，尚非学校模板终稿；封面、声明、目录、字号和页码须按本人的学校正式模板排版。其他同学的毕业设计仅用于观察常见章节层级，不作为本文内容来源。

## 摘要

业务系统更换数据库时，既有数据需要转移到新平台，而迁移期间源库可能继续接收写入。单纯执行一次全量复制会遗漏复制开始后的修改；简单地让全量复制和变更事件同时写入目标库，又可能发生旧的全量批次覆盖较新事件的问题。针对这一具体场景，本文设计并实现了 DataBridge：一个限定于单机、单表、MySQL 到 PostgreSQL 的迁移与增量同步平台。系统用 JDBC 按主键分批执行全量复制，以 Debezium Engine 读取 MySQL Binlog，将增量事件先写入 PostgreSQL 持久日志；全量结束后按顺序回放事件，再转入持续同步。批次写入与复制检查点、事件应用与应用游标分别位于同一目标库事务中，以支持中断后的继续处理。系统还提供源端停写条件下的双探针分块一致性校验，以及任务管理 API 和本机网页控制台。真实双库集成测试覆盖增删改衔接、重复事件、事务回滚、进程重启恢复、类型边界和差异定位。本文不将这一受限实现称为通用异构迁移平台，也不将开发期预实验作为正式性能结论。

**关键词：** 异构数据库迁移；变更数据捕获；MySQL；PostgreSQL；检查点；一致性校验

## Abstract

Database migration must transfer existing records while preserving changes committed during the copy. A one-time copy misses later updates, whereas concurrent full-copy and CDC writes can let an older batch overwrite a newer value. This thesis presents DataBridge, a single-process, single-table MySQL-to-PostgreSQL migration and incremental synchronization system. JDBC keyset pagination performs bounded full copy. Debezium Engine captures MySQL binary-log events and stores them durably before acknowledgment. The system replays these events in order after the copy and then continues streaming. Copy checkpoints and target writes share a transaction; event cursors and applied writes share another. A two-probe, paged verification procedure reports deterministic consistency only when the source remains quiet. Integration tests with real MySQL and PostgreSQL cover handoff, restart, rollback, type boundaries, and difference detection. The implementation is intentionally limited to a defined schema and deployment scope rather than claiming universal cross-database consistency. Formal performance and latency findings will be added only after repeatable experiments are completed.

**Keywords:** database migration; change data capture; MySQL; PostgreSQL; checkpoint; verification

## 1 绪论

### 1.1 研究背景与问题

数据迁移既包含历史存量的搬运，也包含搬运期间新发生的变化。对于仍在营业的系统，源表不一定能长期停写；若全量复制从第一行读到最后一行需要数分钟，复制期间的插入、更新和删除必须被保留。因此核心问题不是“如何把两个数据库连通”，而是如何定义可靠的交接边界，以及故障后如何判断已经完成到哪一步。

常见的错误方案有两类。第一类只在某一时刻导出并导入，它无法覆盖之后的源端提交。第二类先启动 CDC，然后允许 CDC 消费者和全量复制器同时写同一目标表；即使目标写入采用 UPSERT，较晚提交的旧快照仍可能覆盖更早到达的新事件。本文选择全量期间只记录事件、暂不应用到业务目标表，然后先完成全量、再顺序回放。这个写入隔离是平台正确性的主线。

### 1.2 研究目标与意义

本文目标是给出一个范围明确、可运行、可复验的工程实现：在 MySQL 源端持续产生变更和服务可能中断的条件下，将单表迁移到 PostgreSQL，并在源端停写且事件追平后检查最终状态。工程重点包括：固定起始捕获边界、全量与增量无空窗衔接、幂等事件入库、事务性检查点、按序回放、可观察状态和差异报告。Debezium 负责 Binlog 解析，本文的自研部分是围绕捕获事件的存储、搬运、调度、回放与验证；不把第三方解析器的内部能力写成原创算法。

### 1.3 技术背景与相关方案

MySQL 的行级二进制日志提供事务变更的基础信息；本项目固定 ROW 格式和完整行镜像，以便更新事件有足够字段可映射。Debezium MySQL Connector 提供 `no_data` 模式，即捕获相关表结构而不复制业务数据，这使自研 JDBC 全量通道能够承担初始搬运。[MySQL 8.4 Binary Logging 文档](https://dev.mysql.com/doc/refman/8.4/en/replication-options-binary-log.html)与[Debezium MySQL Connector 文档](https://debezium.io/documentation/reference/stable/connectors/mysql.html)描述了相应的日志与快照机制。PostgreSQL `ON CONFLICT` 提供目标主键冲突时的更新路径，但它只解决单次目标写入的冲突，并不自动解决两条写入通道的先后顺序问题。[PostgreSQL 17 INSERT 文档](https://www.postgresql.org/docs/17/sql-insert.html)

Thalheim 与 Wang 从数据模型、模式变换和正确性验证角度研究迁移问题，提示迁移并非只复制字节，还须明确映射语义与验收性质。本文不处理任意模式变换，而是在固定字段映射下通过前置检查拒绝不兼容结构，并把关注点放到在线变更衔接与恢复。[Thalheim 与 Wang，2013](https://www.sciencedirect.com/science/article/pii/S0169023X12001048)

相关研究中的 DBLog 使用水位标记，将按块读取的全量数据与持续到来的变更事件交织处理，使日志流在回填期间仍可前进。本文同样面对“历史快照与新事件如何排序”的问题，但采用更窄的工程选择：全量期间持久保存事件、暂不把事件写到目标业务表，待全量完成后统一回放。它降低了写入竞争的复杂度，代价是全量期间目标业务表并不实时反映最新变化，且事件日志必须容纳积压；因此不应把两种方案说成同一算法。[Andreakis 与 Papapanagiotou，DBLog，2020](https://arxiv.org/abs/2010.12597)

现成迁移工具通常提供更广泛的数据源或部署选项，但本课题选择缩小功能边界，以便对正确性和故障恢复给出测试证据。它不比较未经同条件测试的第三方产品吞吐，也不推断不同数据库之间存在统一的全局事务。

### 1.4 研究内容与章节安排

第 2 章明确支持范围与验收条件；第 3 章描述分层架构、运行流程和持久化模型；第 4 章论证捕获就绪、分批复制、事件回放与校验算法；第 5 章介绍实际工程实现；第 6 章汇总功能测试和可重复实验；第 7 章讨论边界和扩展。所有性能指标应由 `experiments/` 下逐次原始数据计算，不由单次截图或日志推断。

## 2 需求分析

### 2.1 使用场景与角色

使用者在本机配置两个数据库的连接环境变量，预先建立与源表字段兼容、内容为空的 PostgreSQL 业务目标表，再从网页或 HTTP API 创建同步任务。系统先捕获变更，然后复制历史行、回放积压变更并维持增量同步。使用者可查看状态、检查点与事件积压，执行暂停、继续、停止，并在源端停写后发起确定性校验。首版服务仅绑定回环地址且未提供身份认证，不应暴露给局域网或公网。

### 2.2 功能需求

| 编号 | 需求 | 可验证的验收条件 |
|---|---|---|
| F1 | 连接与结构检查 | 显示两端连接与表/列信息；拒绝不兼容主键和目标非空表 |
| F2 | 全量复制 | 按 `id` keyset 分批，固定最大主键上界，写入目标行与推进检查点同事务 |
| F3 | 增量捕获 | 读取 Binlog 的增删改事件，事件入库成功后才确认处理，重复事件由稳定键去重 |
| F4 | 无空窗衔接 | 全量期间不直接应用 CDC 到业务表；完成后按序回放再持续应用 |
| F5 | 恢复与控制 | 暂停/继续/停止可见；进程重启后从持久化位点、检查点与应用游标恢复 |
| F6 | 一致性校验 | 停写、追平时比较行数与规范化数据，报告差异行主键及列名，不泄漏字段原值 |
| F7 | 管理界面 | 浏览器展示任务、真实进度、事件积压与最近报告，不存数据库密码 |

### 2.3 非功能需求与范围

首版只支持 MySQL→PostgreSQL、单机单进程、单表、固定表结构、单列不可变有符号 `BIGINT` 主键。目标表由使用者预先创建且创建任务时必须为空。同一目标表只允许一项任务占用。进程内只允许一个任务运行；尚无跨进程运行锁，不提供多实例部署保证。系统对无法映射的列、异常 Binlog 位点和运行失败采用显式失败，不默默跳过。密码只放在未跟踪环境文件/环境变量中，HTTP 响应不回传连接串或异常原文。性能目标采用实验度量而非事先声称某个吞吐下限。

## 3 系统总体设计

### 3.1 模块和数据流

DataBridge 的源端为 MySQL，目标端 PostgreSQL 同时保存业务表和 `databridge_meta` 元数据。`TaskCoordinator` 串联 `CdcCapture`、`FullCopyWorker` 与 `EventApplier`；`Verifier` 独立在持续同步阶段运行；Spring Boot 提供本机 REST API 和构建后的 Vue 静态资源。

```text
MySQL 业务表 ──JDBC keyset──> FullCopyWorker ──批次事务──> PostgreSQL 业务表
       │                                                   ▲
       └──Binlog──> Debezium Engine ──持久化──> cdc_event ─┘
                                      全量完成后由 EventApplier 顺序回放
```

**交接不变量：**在全量完成标记提交之前，只有 `FullCopyWorker` 写目标业务表；完成之后，全量写入器退出，由 `EventApplier` 按 `sequence` 顺序处理事件。该隔离避免两个写入者互相覆盖。事件入库与 Debezium offset 的刷盘并非同一 PostgreSQL 事务，因此系统不宣称跨组件端到端 exactly-once，而是利用稳定事件键和目标端幂等回放抵御重发。

### 3.2 元数据模型

Flyway V1—V5 在 `databridge_meta` 下建立五类核心记录。`cdc_event` 按任务保存事件顺序号、稳定键、源 partition/offset、操作类型、主键及更新后行；`(task_id,event_key)` 唯一约束去重。`full_checkpoint` 保存一次全量的固定高水位、最后复制主键和完成标记。`event_cursor` 保存最后成功应用的事件顺序号。`sync_task` 保存源/目标表及任务状态，同一目标表有唯一约束。`verification_run` 与 `verification_diff` 保存校验状态、统计量、差异主键和列名，不保存业务原值。数据库迁移版本随源码只追加；Spring Boot 的 Flyway 初始化机制见[官方文档](https://docs.spring.io/spring-boot/how-to/data-initialization.html)。

### 3.3 任务状态

任务从 `CREATED` 启动进入 `CAPTURING`，当前 CDC Engine 捕获就绪后进入 `COPYING`，全量结束转为 `REPLAYING`，积压事件追平后成为 `STREAMING`。`PAUSED` 保留 CDC 捕获而停止新的复制/应用批次，恢复后回到应有阶段。`STOPPED` 和 `FAILED` 分别表示人工停止及异常失败；失败记录阶段和净化后的异常类型/SQLState。进程启动时只恢复唯一一个持久运行任务，避免同一实例并发执行多个任务。

## 4 关键模块详细设计

### 4.1 建任务前置检查

`TaskPreflight` 对源表和目标表检查主键、字段类型与目标是否为空。主键必须是单列有符号 `BIGINT id`，禁止复合键和主键修改。首版映射支持有符号整数、文本、定点数、JSON 与指定时间类型；精度、长度或时间语义不兼容时应拒绝任务，而不是截断字段。MySQL `DATETIME` 作为无时区墙上时间；`TIMESTAMP` 在 UTC 会话下读出，再写 PostgreSQL `timestamp without time zone`。这种约定不等同于跨库时区自动推断，需要在论文和操作手册明确。

### 4.2 当前捕获就绪判定

Engine 启动或 `no_data` 快照结束并不证明本次连接已经保存可恢复 offset。为此，项目在 MySQL 预置 `sync_probe` 表。每次启动或恢复收到快照通知后，写入一个新的 UUID 标记；只有该标记的 CDC 事件已进入目标日志，且本任务的 JDBC offset 存在，才允许全量/回放继续。探针事件参与顺序游标但不写业务表。这避免把固定睡眠时间、其他任务的 offset 或一次性快照通知误当成就绪证据。

### 4.3 全量复制与故障恢复

首次批次读取源表 `MAX(id)` 作为高水位。之后按 `id > last_id AND id <= high_watermark ORDER BY id LIMIT batch_size` 查询；第一页省略下界。稀疏主键不影响扫描终止，后插入且大于高水位的行交由 CDC 捕获。每批在 PostgreSQL 一个事务内执行 UPSERT 并更新 `full_checkpoint.last_id`。若写入失败，检查点与目标行同时回滚；若进程在提交后退出，重启从已提交主键继续。返回空批时记录 `completed=true`，此后不再让全量写入器修改该任务的业务表。

### 4.4 事件持久化和回放

`EventKey` 将源 partition、offset、表名、操作和行主键做稳定序列化并计算 SHA-256；相同源事件重发得到同一键，由数据库唯一约束去重。`CdcCapture` 在事件成功提交到 PostgreSQL 后才向 Debezium 确认。`EventApplier` 只在全量完成后按日志顺序号处理 `c/u/d`；每批目标业务变更与 `event_cursor.last_sequence` 在同一事务提交。失败批次不推进游标，重试仍从原位置开始。持续同步与回放使用同一应用器，差别仅是积压量与任务状态。

两条通道在输入侧不强行复用同一个映射函数：全量从 JDBC `ResultSet` 读取 Java 类型，增量从 Debezium JSON 还原字段值；两者都必须满足预建 PostgreSQL 目标表的字段名、类型、精度和空值契约，且使用 UPSERT 或按主键删除。`FullCopyWorkerIT` 和 `EventApplierIT` 对有符号整数边界、定点小数、Unicode、JSON、NULL 与微秒时间做对应断言，`EndToEndSyncIT` 在真实双库上验证“旧全量值被后续增量覆盖”。这一测试组合降低适配漂移风险，但不构成任意字段值的形式化等价证明。

### 4.5 静默窗口一致性校验

确定性校验要求源端停止业务写入、任务处于 `STREAMING` 且无其他目标表写入者。`Verifier` 先写第一个探针并等待应用游标越过该探针，再对两端按 `id` 分页合并比较，最后写第二个探针并再次等待。若两个探针之间出现业务事件，报告 `INCONCLUSIVE/SOURCE_CHANGED`，不得据此宣称一致或差异确定。若任一探针超时或扫描失败，也报告 `INCONCLUSIVE` 与原因码。正常比较结果为 `CONSISTENT` 或 `DIFFERENT`；差异分为目标缺行、目标多行、同主键值不一致。逐行字段先经 `RowCanonicalizer` 规范化，再比较指纹，并最多保存 100 条差异主键和列名，避免报告泄漏业务原值。

## 5 系统实现

### 5.1 开发与运行环境

后端按 Java 21 目标编译，本机实测 JDK 23.0.2、Maven 3.9.16；使用 Spring Boot 3.5.16、Debezium 3.7.0.Final、MySQL 8.4.11、PostgreSQL 17.11。前端为 Vue 3.5.44、Vite 8.3.4 和 Element Plus 2.14.7。Docker Desktop 运行在 Windows 的 WSL2 后端，开发双库由 Compose 启动。数据库密码仅保存在本机未跟踪的 `infra/.env`，公开仓库只有示例配置。

### 5.2 后端接口与页面

任务 API 提供创建、列表、读取、启动、暂停、继续和停止；进度接口读取持久检查点、捕获/应用事件数和积压数，不通过稀疏主键的最大值伪造百分比。目录接口只读双库表与列元数据，单端失联仅反馈 `reachable=false`。校验 API 创建并查询报告，也可读取最近 20 份历史。构建后的前端资源由同一 Spring Boot 实例服务；默认监听 `127.0.0.1:18080`。网页可在 375px 到桌面宽度下完成任务创建、控制、进度查看和报告浏览。

### 5.3 部署和操作限制

运行顺序为启动 Docker 双库、准备源/目标业务表、构建前端、运行后端、浏览器访问本机地址。目标表须预建且为空，业务源表应保证固定结构与不可变主键。若 Binlog 保留期内的位点已丢失，操作员需人工处理，不应重置位点并将任务状态伪装为正常。项目只允许一个后端进程；未实现认证、跨进程 fencing、DDL 跟踪、自动建表和复杂类型映射。

## 6 系统测试与实验

### 6.1 功能与正确性测试

截至本稿，当前完整后端回归发现 53 项，50 项单元与真实 MySQL/PostgreSQL 集成测试通过，三个独立基准按设计跳过。全量、CDC 延迟及故障恢复基准已分别显式运行，保留 18、300 和 9 条逐次测量。`npm test` 中 9 项前端测试通过，`npm run build` 成功。集成测试用独立 Testcontainers，不读取开发库的密码文件或修改开发数据。测试覆盖全量批次失败时事务回滚、继续复制检查点、CDC 事件入库与去重、全量期间源端增删改后的目标状态、事件应用游标、任务状态控制、源端写入竞争时校验返回 `INCONCLUSIVE`，以及错误响应不回传秘密。逐项测试文件与结论边界见[需求—证据对照](test-evidence-matrix.md)。浏览器手工演示额外验证创建/启动/暂停/继续、源端更新经 CDC 到目标端可见和两次三行一致报告；后端进程强制结束后重启，任务自动恢复为持续同步并保留报告历史。

### 6.2 性能与故障恢复实验

正式全量性能采用 1 万、10 万、100 万行，批次 500/1000，每组 3 次。实验使用独立容器、固定表结构、固定随机顺序、单次全量计时与检查点/目标行数断言；18 条逐次数据、统计脚本和环境说明见[全量通道性能实验报告](../../experiments/full-copy-report.md)。表 6-1 汇总各组中位耗时与平均吞吐，不能用它代表 CDC 或端到端在线迁移性能。

**表 6-1 全量复制性能（3 次技术重复）**

| 行数 | 批次 500 中位耗时 | 批次 1000 中位耗时 | 批次 500 平均吞吐 | 批次 1000 平均吞吐 |
|---:|---:|---:|---:|---:|
| 1 万 | 3.650 s | 2.828 s | 2,737 行/s | 3,525 行/s |
| 10 万 | 34.932 s | 26.759 s | 2,871 行/s | 3,743 行/s |
| 100 万 | 344.829 s | 264.094 s | 2,899 行/s | 3,787 行/s |

同环境下，批次 1000 相对 500 的中位耗时在三个规模分别降低 22.52%、23.40%、23.41%。这一差别不表示对所有字段类型和硬件的最优批次选择。三次测量共享同一对数据库容器，是技术重复；最大 JVM 堆池峰值约 65–66 MiB，且包含测试框架活动。图 6-1、图 6-2 由同一份汇总 CSV 自动生成。

![图 6-1 全量复制中位耗时](../../experiments/figures/full-copy-median-time.png)

![图 6-2 全量复制平均吞吐](../../experiments/figures/full-copy-throughput.png)

此前一轮 1 万行校验预实验只证明扫描可运行，且吞吐包含两个 CDC 探针，不可与表 6-1 的纯复制指标直接比较。

持续同步延迟另在独立 Testcontainers 中用真实 Debezium 和事件应用通道测得。任务达到 `STREAMING` 后，对 100 个不同主键以计划 100 ms 间隔逐条提交更新，三轮共 300 条；PostgreSQL 每约 10 ms 轮询目标值。从源端 `commit()` 调用之前到目标首次被看到的单调时间是“可观察延迟”，包含提交调用与轮询误差，不能称为纯 Binlog 传输延迟。最近秩法统计的结果见表 6-2，完整逐次数据、脚本与有效性威胁见[CDC 延迟实验报告](../../experiments/cdc-latency-report.md)。

**表 6-2 持续 CDC 可观察延迟（每轮 100 条）**

| 轮次 | P50 | P95 | P99 |
|---:|---:|---:|---:|
| 1 | 362.263 ms | 588.316 ms | 693.547 ms |
| 2 | 330.687 ms | 558.900 ms | 590.278 ms |
| 3 | 335.351 ms | 577.681 ms | 630.439 ms |
| 合并描述 | 341.292 ms | 577.380 ms | 630.625 ms |

![图 6-3 三轮 CDC 可观察延迟 ECDF](../../experiments/figures/cdc-latency-ecdf.png)

第一轮有较大的源端提交调用和计划节拍波动，因此三轮差异不应全部归因于 CDC 本身。三轮是同一任务、同一对容器中的技术重复，合并 300 条仅用于描述此次会话。

故障恢复实验另在独立双库中让业务任务运行于子 JVM，父进程分别等到“全量检查点已推进”“CDC 业务事件已持久化但应用游标未推进”“应用事务正在等待目标业务行锁”三个可观察窗口，然后强制终止子 JVM、确认退出并重启同一任务。每窗口三次、每次不同任务，停写追平后逐行比较源/目标。恢复耗时从重启调用计至 `STREAMING` 且全行一致，**包含 JVM 启动与剩余复制/应用**；全量窗口在 1000 行只完成首批 10 行后中断，不能与仅更新一行的另两组直接比较。完整注入条件、9 条逐次数据及边界见[故障恢复实验报告](../../experiments/fault-recovery-report.md)。

**表 6-3 三故障窗口强杀后的恢复结果（每组 3 次）**

| 窗口 | 三次恢复时间 | 中位恢复时间 | 三次最终差异行数 |
|---|---|---:|---|
| 全量写入 | 14.420、14.599、14.276 s | 14.420 s | 0、0、0 |
| CDC 事件入库 | 4.346、4.401、4.218 s | 4.346 s | 0、0、0 |
| 事件回放 | 4.231、4.250、4.226 s | 4.231 s | 0、0、0 |

这证明上述被覆盖的故障条件下恢复后最终一致，不意味着任意时刻掉电、跨进程并发运行或数据库自身故障也有相同结论。三次同宿主、同容器的技术重复不提供生产 SLA 或部署间置信区间。

## 7 总结与展望

在明确限制的单表场景中，DataBridge 已把历史数据搬运与持续变更捕获拆成两条通道，通过先记录事件、后顺序回放的交接策略避免旧全量值覆盖新变更，并以同事务检查点/游标和静默窗口双探针校验提供可检查的恢复与一致性依据。全量性能、稳定小更新下的可观察 CDC 延迟及三故障窗口强杀恢复已有逐次实验数据。JDBC `ResultSet` 与 Debezium JSON 表示不同，因此两条通道保留独立输入适配，以共同的目标 schema 契约及边界/端到端测试约束结果，而不是形式上共用一个 `RowMapper`。现阶段仍缺少单进程以外的互斥与身份认证；不能据限定实验宣称通用数据库迁移或强一致能力。后续可研究更广的类型映射、数据库级租约/fencing、批次写入优化和更多数据库类型，但这些扩展须在既有正确性约束之上逐项验证。

## 参考资料（技术文档，正式论文需按学校格式核对）

1. Debezium. MySQL Connector Documentation. https://debezium.io/documentation/reference/stable/connectors/mysql.html
2. Oracle. MySQL 8.4 Reference Manual: Binary Logging Options and Variables. https://dev.mysql.com/doc/refman/8.4/en/replication-options-binary-log.html
3. PostgreSQL Global Development Group. PostgreSQL 17 Documentation: INSERT. https://www.postgresql.org/docs/17/sql-insert.html
4. Spring Boot Project. Database Initialization Documentation. https://docs.spring.io/spring-boot/how-to/data-initialization.html
5. Andreakis A, Papapanagiotou I. DBLog: A Watermark Based Change-Data-Capture Framework. arXiv:2010.12597, 2020. https://arxiv.org/abs/2010.12597
6. Thalheim B, Wang Q. Data migration: A theoretical perspective. Data & Knowledge Engineering, 87:260–278, 2013. https://doi.org/10.1016/j.datak.2012.12.003

> 参考资料目前以官方技术文档为主；正式提交前需要补充经核实的相关研究文献，并按学校要求统一著录格式。封面作者、导师、原创性声明、签名及日期由本人按真实信息完成。
