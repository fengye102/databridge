# DataBridge 项目规则

## 1. 项目目标与边界

- 项目名称：DataBridge；毕业设计题目为“基于 Spring Boot 的异构数据库迁移与增量同步平台的设计与实现”。
- 第一阶段只支持 MySQL → PostgreSQL、单机部署、单向同步、固定表结构、单列不可变的有符号 BIGINT 主键、预先建好的目标表。
- 正确性优先于功能数量。全量迁移、CDC 捕获、故障恢复和静默窗口下的数据校验构成最小可交付闭环。
- 复合主键、自动建表、在线校验、同事务管理 Debezium 内部位点、复杂 UI 与多节点调度均不属于第一阶段。
- 第一阶段的数据源连接信息由环境变量注入；界面可选择、测试连接，但不保存数据库密码。
- 本规则与设计冲突时，先修改本文件和设计文档，再修改代码。

## 2. 目录与命名

| 路径 | 内容 |
|---|---|
| `backend/` | Spring Boot 后端源码、单元测试和后端构建文件 |
| `backend/src/main/java/com/databridge/` | 后端 Java 代码；按 `cdc/`、`task/`、`copy/`、`verify/` 业务责任分包，不按 controller/service/repository 全局分层 |
| `backend/src/main/java/com/databridge/config/` | Spring Boot 双库连接、CDC 属性与任务运行器装配；配置类名用 `*Configuration`，不存放业务状态或凭据文件 |
| `backend/src/main/java/com/databridge/verify/` | 静默窗口探针、流式行比较和校验报告持久化；类名用 `Verifier`、`Verification*`；源码与报告迁移脚本随版本保留，临时扫描缓冲不落盘 |
| `backend/src/main/resources/application.yml` | 可提交的非秘密运行配置；数据库连接信息只引用环境变量，不写真实密码；与源码一起保留 |
| `backend/src/main/resources/db/migration/` | PostgreSQL 元数据迁移脚本，使用 Flyway `V<序号>__<snake_case>.sql` 命名；只追加新版本，不改已经运行过的版本 |
| `backend/src/main/resources/META-INF/services/` | Java ServiceLoader 服务声明；仅登记需要嵌入式加载的 Debezium 扩展，文件名使用接口全限定名，随源码保留，不作临时清理 |
| `backend/src/test/java/com/databridge/` | 对应生产包的单元与集成测试；集成测试用 `*IT.java` 命名 |
| `backend/src/test/java/com/databridge/verify/` | 校验器类型边界、真实双库差异与源端写入竞争测试；测试容器结束即清理 |
| `backend/target/` | Maven 构建输出，不纳入版本控制；可由 `mvn clean` 重建 |
| `backend/*.ps1` | 本地开发与验证入口；脚本只读未跟踪凭据，不输出密码 |
| `frontend/` | Vue 3 + Vite 管理界面项目根目录；`package.json`/`package-lock.json` 锁定依赖，`index.html` 是入口 |
| `frontend/src/` | 前端单页入口 `main.js`、`App.vue`、`styles.css`；组件文件用 PascalCase，业务函数用 camelCase |
| `frontend/src/components/` | 任务表单、状态/进度和报告展示等可独立测试的 Vue 组件；与页面同生命周期，无生成文件 |
| `frontend/src/api/` | 本机 HTTP 客户端和纯数据变换；不存凭据，文件名用小写 kebab-case，随源码保留 |
| `frontend/tests/` | Vitest 单元与组件测试，文件用 `*.test.js`；临时覆盖率产物不提交 |
| `frontend/node_modules/`、`frontend/dist/` | npm 安装与 Vite 构建输出，不纳入版本控制，可重建清理 |
| `infra/` | Docker Compose、数据库初始化脚本、示例环境变量和固定镜像版本配置 |
| `infra/mysql/init/` | MySQL 首次初始化时执行的最小 SQL 脚本；改动只影响新建数据卷，旧卷需显式迁移 |
| `tests/fixtures/` | 小而确定的 SQL 测试数据及故障注入脚本 |
| `experiments/` | 可复现的实验脚本、参数和已整理的结果；不放大体量原始数据 |
| `experiments/raw/` | 可重新生成的大体量实验原始数据；不纳入版本控制，汇总后清理 |
| `docs/proposals/` | 选题与范围文档；原根目录 v2 文件保留作历史版本 |
| `docs/superpowers/specs/` | 经确认的模块设计与验收标准 |
| `docs/superpowers/plans/` | 与设计一一对应的实施计划 |
| `docs/screenshots/` | 只保存不含凭据/业务原值的固定演示截图，文件名说明页面与视口；更新界面后替换旧截图，不堆积过时版本 |
| `work/` | 可删除的运行日志、临时导出和实验中间文件；不作为交付物 |

- 文档用小写 kebab-case 文件名，版本写在文件名中，例如 `databridge-proposal-v3.md`。
- Java 包名使用 `com.databridge`；类名用 PascalCase，方法和变量用 camelCase。
- 数据库表、列、索引使用 snake_case；元数据放在 PostgreSQL 的 `databridge_meta` schema，业务表放在独立业务 schema。
- 新增目录前，先在本节写明用途、命名和清理方式。
- 开发数据库使用 Docker 命名卷，不把数据库主数据目录绑定到 Windows 工作区；Docker Desktop 的 WSL 数据根目录位于 `D:\Dev Tools Manager\Docker Data`。`docker compose down` 保留数据，执行 `down -v` 前必须明确确认并备份需要的数据。

## 3. 正确性约束

- 全量写入期间，CDC 不得直接写业务目标表。CDC 事件先可靠地写入持久化事件表；全量完成后按源端顺序回放，再持续消费。
- 每个全量批次的目标数据写入与检查点推进必须在同一个 PostgreSQL 事务内。
- CDC 事件持久化成功后才能向 Debezium Engine 确认处理；事件去重、顺序应用与应用游标必须有可验证的实现。
- CDC 事件应用和应用游标推进必须在同一个 PostgreSQL 事务内。首版保留事件记录，不依赖立即清理后的去重行为。
- 全量开始时固定每张表的扫描主键上界，避免持续插入使扫描无界；源端主键更新、DDL 变更和无主键表在第一阶段明确拒绝。
- 运行中出现无法映射的类型、CDC 位点丢失、binlog 被清理或顺序无法保证时，任务进入失败状态，不静默跳过事件。
- 首次复制前必须取得本任务持久化 CDC offset。`no_data` 快照通知本身不产生 offset；源库预置 `sync_probe` 表，CDC 账号仅对其额外具有 INSERT 权限。每次启动或恢复收到快照完成通知后都写入新探针，并等待该探针事件入库及本任务 offset 存在，以证明当前 Engine 已实际捕获；探针事件应用时只推进游标，不写业务表。缺少探针表或权限即拒绝启动，不以固定休眠或其他任务的 offset 代替。
- 首版 `TaskCoordinator` 每实例只运行一个任务，进程内同一任务只允许一个运行器；跨进程并发运行尚无数据库 fencing，部署必须保证单个后端进程。暂停返回后不得再启动新的复制/回放批次，CDC 继续入日志；停止等待运行器和 Engine 退出。
- 任务失败仅持久化异常类型链和 SQLState，不持久化原始异常文本、连接串或密码。
- 首版 HTTP API 只绑定 `127.0.0.1`，未实现身份认证，不向局域网或公网暴露；响应不得包含数据库密码、JDBC URL 或原始异常文本。
- 管理界面只消费本机 API，不在浏览器存数据库密码；任务进度以真实持久化检查点和事件游标为准，不用稀疏主键范围伪装完成百分比。页面轮询在隐藏标签或卸载后暂停，不把 `INCONCLUSIVE` 显示成一致。
- `frontend/dist/` 由 `npm run build` 生成且仍不提交；本机 Spring Boot 可从该目录直接服务前端静态文件，访问地址与 API 同为 `127.0.0.1:18080`。Vite `5173` 仅用于开发热更新，均只绑定本机。
- 首版时间映射固定为：MySQL `DATETIME` 按无时区墙上时间写入 PostgreSQL `timestamp without time zone`；MySQL `TIMESTAMP` 在源连接会话设为 UTC 后，按 UTC 墙上时间写入同类目标列。任务创建时须拒绝不同的目标时间列类型；未来 `RowMapper` 需让 CDC 与全量路径采用同一语义。
- 只在源端停止写入、CDC 追平后进行确定性一致性校验；在线校验属于扩展研究。
- 校验前后各写一次同任务探针并等待应用游标越过探针；若两个探针之间有业务 CDC 事件，报告为 `INCONCLUSIVE`，不得宣称一致或差异确定。差异报告只保存行主键与不一致列名，不保存业务字段原值。

## 4. 开发与验证

- 开发环境为 Windows + PowerShell。命令和路径按本机环境编写，不复制 macOS/Linux 命令作为可执行步骤。
- 每个功能先写失败测试，再实现最小功能；集成测试覆盖真实 MySQL、PostgreSQL 与进程中断恢复。
- 版本号在首次集成验证后固定；以项目文件记录 Java、Spring Boot、Debezium、MySQL、PostgreSQL 的实际版本。
- 每项实现必须给出可重复的验证命令和结果；未运行的测试不得写成“已通过”。
- 数据库密码和连接串通过环境变量或本地未跟踪配置注入，不提交真实凭据。
- 本地双库环境仅用于开发：MySQL 主机端口 `127.0.0.1:3307`，PostgreSQL 主机端口 `127.0.0.1:15433`；固定补丁版本镜像，MySQL 启用 ROW/FULL Binlog。真实密码写入未跟踪的 `infra/.env`，仓库只保留 `infra/.env.example`。Windows 的动态保留端口可能覆盖 5433，因此 PostgreSQL 改用 15433；调整端口前先检查 `netsh interface ipv4 show excludedportrange protocol=tcp`。
- 本机 HTTP 默认端口为 `127.0.0.1:18080`，Vite 开发代理指向同一地址。当前机器 `127.0.0.1:8080` 无监听进程却拒绝绑定，改端口前先实测 TCP 绑定；不因此开放外网监听。

## 5. 清理与保留

- `work/` 下只放临时产物。清理前核对目标绝对路径处于项目工作区内；不要对计算出的未知路径执行递归删除。
- `experiments/` 保留脚本、参数、汇总 CSV、图表和说明；百万行生成数据、Docker 命名卷和日志不纳入版本控制。
- 原始选题文档、经确认的设计、测试样本及论文所需实验结果保留，不以“整理目录”为由覆盖或删除。

## 6. 当前状态

- 原始文档为项目根目录的 `基于_Spring_Boot_的异构数据库迁移与增量同步平台的设计与实现_v2.md`。
- 已建立公开 Git 仓库 `fengye102/databridge`；2026-10-19 用户确认开始按核心链路设计实施。先验证真实 CDC 捕获、事件持久化、去重、顺序与恢复，再进入全量迁移；前端最后开发。
- CDC 事件入库、持久就绪探针、FullCopyWorker、EventApplier、任务创建前校验、TaskCoordinator、本机 HTTP 运行入口、Verifier/报告 API 与 Vue 运维台已实现；最终完整回归为后端 50 项单元及真实双库测试、前端 9 项测试、生产构建通过。本机演示已完成任务创建、启动、暂停、继续、源端更新后目标端 CDC 可见、两次一致校验；后端进程强制终止后重启，任务自动恢复 `STREAMING` 且历史报告保留。当前尚未实现共享 RowMapper、正式大规模三次实验及论文正文。依赖版本记录：本机 JDK 23.0.2、Maven 3.9.16；后端按 Java 21 编译，Spring Boot 3.5.16、Debezium 3.7.0.Final；数据库 MySQL 8.4.11、PostgreSQL 17.11；前端 Node.js 24.18.0、npm 11.16.0、Vue 3.5.44、Vite 8.3.4、Element Plus 2.14.7。
