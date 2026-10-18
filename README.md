# DataBridge

基于 Spring Boot 的 MySQL → PostgreSQL 全量迁移与增量同步毕业设计项目。

**当前阶段：CDC、全量复制、事件回放和任务创建校验已通过真实双库测试，完整任务编排尚未完成。** 核心链路采用“先捕获并持久化 CDC 事件、执行 JDBC 全量、再按序回放并持续同步”，避免迟到的全量批次覆盖新事件。任务状态机、校验报告、HTTP API 和前端仍待实现。

## 文档

- [项目规则](Codex.md)
- [选题方案 v3](docs/proposals/databridge-proposal-v3.md)
- [核心链路设计](docs/superpowers/specs/2026-10-18-databridge-core-design.md)
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

MySQL 连接：`127.0.0.1:3307`，库 `databridge_source`，用户 `databridge`，密码为 `infra/.env` 的 `MYSQL_PASSWORD`；该用户对业务源表只读，另有 CDC 复制权限及对 `sync_probe` 的 INSERT 权限。PostgreSQL 连接：`127.0.0.1:15433`，库 `databridge_target`，用户 `databridge`，密码为 `POSTGRES_PASSWORD`。两个库目前没有业务表，MySQL 已预置 `sync_probe`。

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

`CdcCapture.awaitReady` 不把 Engine 启动或 `no_data` 快照结束误判为可恢复。它收到快照完成通知后写入同一源库的 `sync_probe`，直到本任务的 JDBC offset 持久化才允许全量复制；探针事件入日志，回放时仅推进游标。`TaskPreflight` 在创建任务前检查两端单列 `id` 主键、列兼容性、目标空表和探针表；`sync_task` 对目标表设置唯一约束。运行时不保存源/目标数据库密码。

集成测试使用 Testcontainers 临时创建双库和故障注入对象，结束后自动清理，不读取或修改 `infra/.env` 与开发数据库数据。此阶段的 Spring Boot 入口尚未接入任务管理和 HTTP API；运行入口本身不会启动同步任务。下一步实现任务状态机和静默窗口一致性校验，再接入 API。
