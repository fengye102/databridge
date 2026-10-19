# DataBridge

基于 Spring Boot 的 MySQL → PostgreSQL 全量迁移与增量同步毕业设计项目。

**当前阶段：CDC、全量复制、事件回放、任务状态机、本机 HTTP 入口、静默窗口校验及 Vue 管理界面已实现。** 核心链路采用“先捕获并持久化 CDC 事件、执行 JDBC 全量、再按序回放并持续同步”，避免迟到的全量批次覆盖新事件。最新后端回归 50 项通过、独立基准 1 项有意跳过；前端 9 项通过。全量通道的 18 次正式性能测量已完成，CDC 延迟与故障窗口正式实验仍待执行。

## 文档

- [项目规则](Codex.md)
- [选题方案 v3](docs/proposals/databridge-proposal-v3.md)
- [核心链路设计](docs/superpowers/specs/2026-10-18-databridge-core-design.md)
- [静默窗口校验设计](docs/superpowers/specs/2026-10-19-databridge-verification-design.md)
- [本机运维台设计](docs/superpowers/specs/2026-10-19-databridge-console-design.md)
- [运维台桌面截图](docs/screenshots/databridge-console-desktop.png) / [手机宽度截图](docs/screenshots/databridge-console-mobile.png)
- [1 万行校验预实验记录](experiments/verification-10000.md)
- [全量通道正式实验报告](experiments/full-copy-report.md)、[逐次 CSV](experiments/full-copy-runs.csv)、[六组汇总](experiments/full-copy-summary.csv)
- [毕业设计正文技术草稿](docs/thesis/databridge-thesis-draft.md)、[测试证据对照](docs/thesis/test-evidence-matrix.md)、[答辩提纲](docs/thesis/defense-outline.md)
- [本地双库环境设计](docs/superpowers/specs/2026-10-18-databridge-local-infra-design.md)
- [历史选题方案 v2](基于_Spring_Boot_的异构数据库迁移与增量同步平台的设计与实现_v2.md)

## 第一阶段范围

单机、单表、MySQL → PostgreSQL、单列不可变有符号 BIGINT 主键、预先建好的目标表。优先完成真实 CDC 捕获、可恢复全量迁移、事件回放和静默窗口下的一致性校验，再开发管理界面。

## 本地开发数据库

环境使用 MySQL 8.4.11 和 PostgreSQL 17.11，分别只映射到 Windows 的 `127.0.0.1:3307`、`127.0.0.1:15433`。MySQL 开启 7 天保留的 ROW/FULL Binlog，并给 `databridge` 用户授予后续 CDC 所需权限。数据库数据保存在 Docker 命名卷内；本机 Docker Desktop 的 WSL 数据盘位于 `D:\Dev Tools Manager\Docker Data`。PostgreSQL 使用 15433 是因为 Windows 重启后动态保留了原先的 5433 端口。

此机器已生成未跟踪的 `infra/.env`。新机器需要复制 `infra/.env.example` 到 `infra/.env`，把三个占位值换成不同的随机密码后再启动。不要提交该文件或在命令行中直接输出密码。

PowerShell 启动与验证：

```powershell
$repo = 'D:\code\Program\毕设'
$docker = 'D:\Dev Tools Manager\Docker Desktop\resources\bin\docker.exe'
$env:PATH = "$(Split-Path $docker);$env:PATH"
& $docker compose --env-file "$repo\infra\.env" -f "$repo\infra\compose.yaml" up -d --wait
& 'D:\Dev Tools Manager\7\pwsh.exe' -NoProfile -File "$repo\infra\verify.ps1"
```

MySQL 连接：`127.0.0.1:3307`，库 `databridge_source`，用户 `databridge`，密码为 `infra/.env` 的 `MYSQL_PASSWORD`；该用户对业务源表只读，另有 CDC 复制权限及对 `sync_probe` 的 INSERT 权限。PostgreSQL 连接：`127.0.0.1:15433`，库 `databridge_target`，用户 `databridge`，密码为 `POSTGRES_PASSWORD`。空数据卷初始化时没有业务表，MySQL 已预置 `sync_probe`；演示表由下述脚本额外创建。

停止环境但保留数据：

```powershell
& $docker compose --env-file "$repo\infra\.env" -f "$repo\infra\compose.yaml" down
```

`down` 保留命名卷；`down -v` 会删除数据库数据，执行前先备份。MySQL 初始化授权脚本只在空数据卷首次启动时运行，修改脚本不会自动改动已有数据库。

既有数据卷需单独执行新增的探针迁移（保持数据卷和业务表不变）：

```powershell
Set-Location 'D:\code\Program\毕设\infra'
Get-Content -LiteralPath '.\mysql\init\02-sync-probe.sql' -Raw |
  & $docker compose --env-file .env -f compose.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
```

## 后端 CDC 与全量复制基础链路

`backend/pom.xml` 固定 Spring Boot 3.5.16、Debezium 3.7.0.Final 和 Java 21 编译目标；本机使用 JDK 23、Maven 3.9.16。测试数据库版本为 MySQL 8.4.11、PostgreSQL 17.11。CDC 采用 `snapshot.mode=no_data`、单线程接收，事件先在 PostgreSQL 事务中持久化，再向 Debezium 确认；offset 和 schema history 也保存在 PostgreSQL 的 `databridge_meta` schema。

PowerShell 验证命令（需先启动 Docker Desktop）：

```powershell
Set-Location 'D:\code\Program\毕设\backend'
mvn test
mvn clean verify -Pintegration
```

`FullCopyWorker` 在调用方确认 CDC 已就绪后初始化固定的 `MAX(id)` 高水位，以有符号 BIGINT `id` 做 keyset 分批复制。每批 PostgreSQL UPSERT 与 `full_checkpoint.last_id` 在同一事务；空批标记完成，重新调用从检查点继续。源端 `TIMESTAMP` 会话设为 UTC，`DATETIME` 保留无时区墙上时间，目标时间列为 `timestamp without time zone`；JSON 目标列为 `jsonb`。不兼容的列类型、精度或长度会被拒绝。全量期间 CDC 仍只入事件日志，不直接覆盖业务目标表。

`EventApplier` 仅在全量检查点标记完成后运行，按 `cdc_event.sequence` 回放 `c/u/d`，并将目标行变更与 `event_cursor.last_sequence` 在同一 PostgreSQL 事务提交。它会拒绝错误来源表、缺列、主键不符和不支持的目标类型；失败批次不推进游标。真实 Binlog 贯通测试已覆盖全量期间更新与删除、低于固定高水位的新插入，以及 DECIMAL、JSON、DATETIME/TIMESTAMP 的最终值。

`CdcCapture.awaitReady` 不把 Engine 启动或 `no_data` 快照结束误判为可恢复。每次启动或恢复收到快照通知后，它向同一源库的 `sync_probe` 写入新标记，等待该标记事件入库及本任务 JDBC offset 存在，确认当前 Engine 实际捕获后才允许全量复制或恢复应用；探针事件回放时仅推进游标。`TaskPreflight` 在创建任务前检查两端单列 `id` 主键、列兼容性、目标空表和探针表；`sync_task` 对目标表设置唯一约束。运行时不保存源/目标数据库密码。

`TaskCoordinator` 提供创建、启动、暂停、恢复、停止、状态读取和关闭。每实例只运行一个任务；进程内阻止同一任务重复启动。`PAUSED` 保持 CDC 捕获但停止复制/应用，`STOPPED` 等待运行器和 Engine 退出；再启动从持久检查点/游标恢复。`FAILED` 保留阶段及不含凭据的异常类型/SQLState。每任务 CDC 前缀由任务 UUID 稳定派生，进程重启时自动恢复唯一一个持久运行任务。首版必须单进程部署，跨进程运行锁尚未实现。

集成测试使用 Testcontainers 临时创建双库和故障注入对象，结束后自动清理，不读取或修改 `infra/.env` 与开发数据库数据。

## 本机 HTTP 运行入口

先启动上述 Compose 双库，再在另一 PowerShell 终端运行：

```powershell
& 'D:\code\Program\毕设\backend\run-local.ps1'
```

脚本只读取未跟踪的 `infra/.env`，将 MySQL/PostgreSQL 用户和密码传给当前 Maven/Java 进程，不显示密码；也可自行设置 `DATABRIDGE_SOURCE_JDBC_URL`、`DATABRIDGE_SOURCE_USERNAME`、`DATABRIDGE_SOURCE_PASSWORD`、`DATABRIDGE_TARGET_JDBC_URL`、`DATABRIDGE_TARGET_USERNAME`、`DATABRIDGE_TARGET_PASSWORD` 后在 `backend/` 执行 `mvn spring-boot:run`。CDC 连接使用相同的源库账号；远程数据库还应设置 `DATABRIDGE_MYSQL_HOST`、`DATABRIDGE_MYSQL_PORT`、`DATABRIDGE_MYSQL_DATABASE` 与 `DATABRIDGE_MYSQL_SERVER_ID`。每任务 CDC 前缀固定为 `databridge_<task-uuid>`，不受进程配置变化影响。默认复制批次为 500，可用 `DATABRIDGE_COPY_BATCH_SIZE` 调整。

Spring Boot 启动时由 Flyway 自动迁移目标库元数据。HTTP **只监听 `127.0.0.1:18080`，没有身份认证，不得直接向局域网或公网暴露**。本机 `8080` 当前无法绑定，已验证 `18080` 可用；如需换端口，同时修改后端环境变量 `DATABRIDGE_HTTP_PORT` 与前端 Vite 代理。创建任务前须手动建好同结构、空的 PostgreSQL 目标业务表；MySQL 源表须符合项目规则。

```powershell
$api = 'http://127.0.0.1:18080/api/tasks'
$body = @{ sourceSchema = 'databridge_source'; sourceTable = 'items'; targetSchema = 'business'; targetTable = 'items' } | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri $api -ContentType 'application/json' -Body $body
$id = $task.taskId
Invoke-RestMethod -Method Post -Uri "$api/$id/start"
Invoke-RestMethod -Method Get -Uri "$api/$id"
Invoke-RestMethod -Method Post -Uri "$api/$id/pause"
Invoke-RestMethod -Method Post -Uri "$api/$id/resume"
# 源端停止写入，等待状态为 STREAMING 后再做确定性校验
$report = Invoke-RestMethod -Method Post -Uri "$api/$id/verifications"
Invoke-RestMethod -Method Get -Uri "$api/$id/verifications/$($report.runId)"
Invoke-RestMethod -Method Post -Uri "$api/$id/stop"
```

`start` 异步返回 202；通过查询状态等待 `STREAMING`。校验接口同步返回 201，报告为 `CONSISTENT`、`DIFFERENT` 或 `INCONCLUSIVE`；后者含原因码，表示源端窗口内写入、探针超时或扫描失败，不能作为一致结论。校验采用前后两个 CDC 探针，等应用游标追平，再按 `id` 分批比较；只记录行数、主键与最多 100 条不一致列名，不存业务字段原值。源端必须停写才能获得确定性结论；目标端也不应有其他写入者。默认校验批次 500、每个探针超时 30 秒，可用 `DATABRIDGE_VERIFICATION_BATCH_SIZE`、`DATABRIDGE_VERIFICATION_TIMEOUT_SECONDS` 调整。另有 `GET /api/catalog`、`GET /api/tasks`、`GET /api/tasks/{id}/progress` 和 `GET /api/tasks/{id}/verifications`，分别用于连接/表结构检查、任务列表、持久进度和最近 20 份报告。非法请求返回 400、目标表已被任务占用返回 409、未知任务或报告返回 404、非法状态返回 409；API 不返回原始异常或凭据。

## Vue 本机运维台与演示

前端固定 Vue 3.5.44、Vite 8.3.4、Element Plus 2.14.7，使用 Node.js 24.18.0/npm 11.16.0；`frontend/package-lock.json` 锁定传递依赖。普通演示只需构建前端，然后启动后端，同一端口即可访问页面和 API：

```powershell
Set-Location 'D:\code\Program\毕设\frontend'
npm ci
npm run build
& 'D:\code\Program\毕设\backend\run-local.ps1'
# 浏览器打开 http://127.0.0.1:18080/
```

开发前端时，可另开 PowerShell 使用 Vite 热更新：

```powershell
Set-Location 'D:\code\Program\毕设\frontend'
npm ci
npm run dev
# 浏览器打开 http://127.0.0.1:5173/
```

Vite 只绑定 `127.0.0.1:5173`，`/api` 转发到 `127.0.0.1:18080`。页面提供双库连通与表元数据、任务创建、启动/暂停/继续/停止、真实复制检查点与事件积压、最近校验报告。隐藏浏览器标签时暂停 3 秒轮询；不保存数据库密码或行值，不把主键范围当成复制百分比。屏幕最窄 375px 无横向滚动。

准备可重复的三行演示表（脚本只 `CREATE IF NOT EXISTS` / `INSERT IGNORE`，不会删除既有数据）：

```powershell
& 'D:\code\Program\毕设\tests\fixtures\load-demo.ps1'
```

然后在页面选择 `databridge_source.demo_items` → `business.demo_items`，创建并启动任务。等待“持续同步”后停止源端写入，运行校验，报告应显示三行一致。**目标表必须为空才能创建新任务**；本机演示已创建过任务时，直接打开既有任务，不要重复建同名目标任务。实测在页面完成创建、启动、暂停、继续与两次一致性校验；源表更新后目标值通过 CDC 可见。桌面/375px 审计均为 0 个自动检测违规项（另有 3 个装饰箭头需要人工复核对比度）。

前端验证：

```powershell
Set-Location 'D:\code\Program\毕设\frontend'
npm test
npm run build
```

## 尚待论文阶段完成

第一阶段单机单表实现和全量通道正式实验已完成。全量性能的完整方法、18 条逐次测量和图表见[实验报告](experiments/full-copy-report.md)：100 万行在批次 500/1000 下的中位耗时分别为 344.829 秒和 264.094 秒，**不含 CDC 与校验耗时**。还需要按[选题方案 v3](docs/proposals/databridge-proposal-v3.md)执行 CDC 延迟和三个故障窗口的重复实验，并将结果写入论文正文；当前正文只是技术草稿，正式 DOCX 格式仍须按学校模板核对。共享 `RowMapper` 的代码去重尚未完成；目前全量和 CDC 类型边界由各自测试保证，不应把当前实现描述为通用异构迁移平台。
