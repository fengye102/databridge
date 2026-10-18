# DataBridge 本地双库环境设计

> 状态：2026-10-18 已确认；仅覆盖开发环境，不代表核心同步设计获确认。

## 目标

在已安装的 Docker Desktop WSL2 后端上，用一个 Compose 项目启动可重复的 MySQL 源库与 PostgreSQL 目标库，为后续真实 CDC 集成测试提供基础。验收以容器健康、主机端口、MySQL ROW/FULL Binlog 和最小权限账户为准；本阶段不编写同步服务或业务表。

## 配置

- 官方镜像固定补丁版本：`mysql:8.4.11`、`postgres:17.11`。只绑定 `127.0.0.1:3307` 和 `127.0.0.1:15433`，避免暴露到局域网，也避开 Windows 当前保留的 5433 端口。[MySQL 官方镜像](https://hub.docker.com/_/mysql)、[PostgreSQL 官方镜像](https://hub.docker.com/_/postgres)
- 数据库主数据使用 Docker 命名卷。Docker Desktop 的 WSL 数据根目录已配置在 `D:\Dev Tools Manager\Docker Data`；不把数据库数据目录绑定到 NTFS 工作区。
- MySQL 设置唯一 `server-id`、开启 Binlog、`ROW` 格式、`FULL` 行镜像，保留 7 天用于开发测试。首次初始化创建 `databridge_source` 和 `databridge` 用户；初始化脚本撤销镜像默认授予的库级 ALL，只保留源库 SELECT 与 Debezium 所需的复制/元数据权限。[Debezium MySQL 前置条件](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- PostgreSQL 首次初始化创建 `databridge_target` 和 `databridge` 用户。暂不建目标业务表或元数据表。
- 密码只在未跟踪的 `infra/.env` 中保存；仓库只提供变量名示例。容器具有健康检查；停止服务保留命名卷，删除卷须显式执行带 `-v` 的命令。

## 验收

1. `docker compose config --quiet` 成功；两容器健康。
2. 主机回环地址的 3307、15433 端口可连接。
3. MySQL 版本为 8.4.11，`log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`，`databridge` 用户可读取 Binlog 状态。
4. PostgreSQL 版本为 17.11，`databridge` 用户可连接 `databridge_target`。
5. 两个命名卷在 Docker WSL 数据盘中持久化，`docker compose down` 后可重新启动且数据保留。
