# DataBridge Local Infra Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 提供固定版本、可验证的本地 MySQL/PostgreSQL 开发环境。

**Architecture:** 单个 Docker Compose 项目管理两数据库及其命名卷；MySQL 首次初始化脚本只补充 CDC 权限。凭据由忽略的 `infra/.env` 注入，验证脚本只读取环境变量而不输出密码。

**Tech Stack:** Docker Desktop WSL2、Docker Compose、MySQL 8.4.11、PostgreSQL 17.11、PowerShell 7。

**Spec:** `docs/superpowers/specs/2026-10-18-databridge-local-infra-design.md`

## Global Constraints

- 只绑定 127.0.0.1；密码不入 Git。
- 命名卷位于 Docker Desktop 的 D 盘 WSL 数据根目录；普通停止不删除卷。
- 本计划不实现同步后端、业务表或 UI。

---

### Task 1: 可复现的双库 Compose 配置

**Files:** `infra/compose.yaml`, `infra/.env.example`, `infra/mysql/init/01-cdc-grants.sql`, `.gitignore`

- [x] 先运行缺失配置的验证命令，确认失败点是 Compose 文件尚不存在。
- [x] 写最小 Compose、MySQL 授权脚本与本地环境变量示例。
- [x] 生成未跟踪的 `infra/.env`，运行 `docker compose config --quiet`。
- [x] 启动服务，检查两个容器健康、版本、端口、Binlog 与授权。

### Task 2: 重复验证与使用说明

**Files:** `infra/verify.ps1`, `README.md`

- [x] 先写验证脚本并在空/未启动环境确认其按预期失败。
- [x] 用已启动环境运行验证脚本，记录实际结果。
- [x] 在 README 中写 Windows/PowerShell 启停与数据保留说明。
- [x] 检查 `git status` 无凭据和数据卷，提交并推送已验证的文件。

## 验证记录（2026-10-18）

- 配置文件缺失时 `docker compose config --quiet` 退出码为 1；加入配置后为 0。
- 停止两个容器后，`infra/verify.ps1` 因 MySQL 未健康而退出 1；重启后通过。
- 初始 MySQL 用户获得过宽的数据库权限，新增只读断言先失败；收紧授权并应用到已有数据卷后通过。
- 使用独立临时命名卷重走首次初始化，确认新建 MySQL 用户具有 SELECT 和复制权限而没有库级 ALL；临时卷已删除，原双库环境已恢复。
- MySQL 报告 `8.4.11 / log_bin=1 / ROW / FULL / 604800`，PostgreSQL 报告 `17.11`；两个本地端口连通。
- 在两库写入临时标记后执行 `docker compose down` 和 `up --wait`，标记仍在；已删除临时标记表。
- 最后一次 `infra/verify.ps1` 输出 `PASS`；提交前还要重新运行完整验证。
