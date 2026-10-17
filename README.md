# DataBridge

基于 Spring Boot 的 MySQL → PostgreSQL 全量迁移与增量同步毕业设计项目。

**当前阶段：本地双库环境已可运行，核心链路设计待确认。** 核心链路拟采用“先捕获并持久化 CDC 事件、执行 JDBC 全量、再按序回放并持续同步”，避免迟到的全量批次覆盖新事件。当前仓库尚无同步服务程序。

## 文档

- [项目规则](Codex.md)
- [选题方案 v3](docs/proposals/databridge-proposal-v3.md)
- [核心链路设计](docs/superpowers/specs/2026-10-18-databridge-core-design.md)
- [本地双库环境设计](docs/superpowers/specs/2026-10-18-databridge-local-infra-design.md)
- [历史选题方案 v2](基于_Spring_Boot_的异构数据库迁移与增量同步平台的设计与实现_v2.md)

## 第一阶段范围

单机、单表、MySQL → PostgreSQL、单列不可变有符号 BIGINT 主键、预先建好的目标表。优先完成真实 CDC 捕获、可恢复全量迁移、事件回放和静默窗口下的一致性校验，再开发管理界面。

## 本地开发数据库

环境使用 MySQL 8.4.11 和 PostgreSQL 17.11，分别只映射到 Windows 的 `127.0.0.1:3307`、`127.0.0.1:5433`。MySQL 开启 7 天保留的 ROW/FULL Binlog，并给 `databridge` 用户授予后续 CDC 所需权限。数据库数据保存在 Docker 命名卷内；本机 Docker Desktop 的 WSL 数据盘位于 `D:\Dev Tools Manager\Docker Data`。

此机器已生成未跟踪的 `infra/.env`。新机器需要复制 `infra/.env.example` 到 `infra/.env`，把三个占位值换成不同的随机密码后再启动。不要提交该文件或在命令行中直接输出密码。

PowerShell 启动与验证：

```powershell
$repo = 'D:\code\Program\毕设'
$docker = 'D:\Dev Tools Manager\Docker Desktop\resources\bin\docker.exe'
$env:PATH = "$(Split-Path $docker);$env:PATH"
& $docker compose --env-file "$repo\infra\.env" -f "$repo\infra\compose.yaml" up -d --wait
& 'D:\Dev Tools Manager\7\pwsh.exe' -NoProfile -File "$repo\infra\verify.ps1"
```

MySQL 连接：`127.0.0.1:3307`，库 `databridge_source`，用户 `databridge`，密码为 `infra/.env` 的 `MYSQL_PASSWORD`；该用户只读源库，另有 CDC 所需的复制权限。PostgreSQL 连接：`127.0.0.1:5433`，库 `databridge_target`，用户 `databridge`，密码为 `POSTGRES_PASSWORD`。两个库目前没有业务表。

停止环境但保留数据：

```powershell
& $docker compose --env-file "$repo\infra\.env" -f "$repo\infra\compose.yaml" down
```

`down` 保留命名卷；`down -v` 会删除数据库数据，执行前先备份。MySQL 初始化授权脚本只在空数据卷首次启动时运行，修改脚本不会自动改动已有数据库。

实施同步服务前请先阅读 `Codex.md` 与核心链路设计，并完成该设计的书面确认。
