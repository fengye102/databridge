# DataBridge Runtime API Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 通过环境变量启动双库 Spring Boot 服务，在本机 HTTP 接口管理已实现的单表同步任务。

**Architecture:** 目标 PostgreSQL 为 `@Primary DataSource`，Spring Flyway 自动迁移元数据；源 MySQL 单独注入。`TaskCoordinator` 是单例，HTTP 控制器只转换请求/响应并调用现有状态机。崩溃后启动时只自动恢复唯一一个处于运行阶段的任务；暂停、停止与失败任务不自动运行。

**Tech Stack:** Java 21、Spring Boot 3.5.16 Web/JDBC、Flyway、Debezium 3.7.0.Final、Testcontainers MySQL 8.4.11/PostgreSQL 17.11。

**Spec:** `docs/superpowers/specs/2026-10-18-databridge-core-design.md` 第 3、4、7 节；`Codex.md`。

## Global Constraints

- HTTP 仅监听 `127.0.0.1`；不带认证，禁止直接对外暴露。
- 密码和 JDBC URL 只来自进程环境；响应/任务表不记录凭据及异常原文。
- 每个任务稳定且唯一的 `topic.prefix`，恢复时不得改变。
- 单个后端进程、单个活动 `TaskCoordinator`；超过一个待恢复任务时拒绝自动任选。
- TDD：先执行失败的真实双库接口测试，再实现。

---

## File Map

| 文件 | 职责 |
|---|---|
| `backend/pom.xml` | 增加 Spring MVC/Web |
| `backend/src/main/resources/application.yml` | 环境变量映射、仅本机 HTTP 绑定 |
| `backend/src/main/java/com/databridge/config/RuntimeConfiguration.java` | 双 DataSource、Debezium 基本属性、Coordinator 装配 |
| `backend/src/main/java/com/databridge/task/TaskController.java` | REST 创建、查询、启动、暂停、恢复、停止 |
| `backend/src/main/java/com/databridge/task/TaskApiErrors.java` | HTTP 400/404/409/500 的安全错误格式 |
| `backend/src/main/java/com/databridge/task/TaskRepository.java` | 唯一活动任务查询 |
| `backend/src/main/java/com/databridge/task/TaskRecovery.java` | 启动时自动恢复唯一活动任务 |
| `backend/src/main/java/com/databridge/task/TaskCoordinator.java` | 每任务稳定的 CDC `topic.prefix` |
| `backend/src/test/java/com/databridge/task/TaskApiIT.java` | 真实双库、Flyway 和 HTTP 生命周期 |
| `README.md` | 运行环境变量、启动命令、接口示例与限制 |

### Task 1: 配置、HTTP 生命周期

- [x] 编写 `TaskApiIT`：动态注入容器 JDBC/MySQL CDC 属性，确认 Flyway 自动迁移；POST `/api/tasks` 创建，GET `/api/tasks/{id}` 返回状态；POST `/{id}/start` 后等待 `STREAMING`，更新源行后目标追平；pause/resume/stop；错误路径不泄漏密码。
- [x] 执行 `mvn --batch-mode -q '-Dit.test=TaskApiIT' verify -Pintegration`，确认新增测试先因无 Web、后因路由 404 失败。
- [x] 实现双库配置和本地绑定；`TaskController` 不保存凭据，返回任务 ID、表映射、状态、恢复状态和安全错误原因；冲突返回 409，非法表返回 400，不存在返回 404，未预期错误返回不含内部文本的 500。
- [x] 专项测试通过；非法 UUID 的 400 测试曾先以 500 失败，再修复。

### Task 2: 自动恢复与前缀隔离

- [x] 添加测试：已持久化的单个活动任务可由 `TaskRecovery` 启动；两个活动任务触发明确启动失败；不同任务生成不同而重启稳定的 CDC 前缀。
- [x] 运行测试，确认缺失实现和配置变更导致的前缀不稳定均曾失败。
- [x] `TaskRepository` 查询活动阶段（`CAPTURING/COPYING/REPLAYING/STREAMING`）；`TaskRecovery` 在应用启动后恢复唯一任务；`TaskCoordinator` 从固定 `databridge_` 和任务 UUID 派生 CDC 前缀，不受环境变量变更影响。
- [x] 2026-10-19 全套 `mvn --batch-mode -q clean verify -Pintegration`：45 tests、0 failures/errors/skips。

### Task 3: 本地运行与交付

- [x] 用现有 Docker Compose 双库、未跟踪 `infra/.env` 启动后端；本机 `GET /api/tasks/{unknown}` 实际返回安全 404，`netstat` 确认仅监听 `127.0.0.1:8080`。
- [ ] 更新 README/Codex 当前状态；执行 `git diff --check`、全量测试；提交并推送独立分支，创建以 `codex/task-coordinator` 为 base 的堆叠 PR。

## Exit Gate

本计划不实现 Verifier、校验报告、前端、多进程 fencing 或远程鉴权。上述未实现功能在 README 中明确标记，不用 API 可达性冒充完整平台。
