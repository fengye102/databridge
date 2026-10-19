# DataBridge Console Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 交付单页本机工作台，完成任务创建/控制、状态与真实进度展示、连接和表结构检查、校验历史展示。

**Architecture:** Spring Boot 增加只读 catalog/list/progress/report-list 接口；Vue 3 + Vite + Element Plus 通过本机 `/api` 代理消费现有与新增接口。所有业务状态仍以后端 PostgreSQL 持久数据为准。

**Tech Stack:** Java 21/Spring Boot 3.5.16/JDBC，Vue 3、Vite、Element Plus、Vitest，Node.js 24.18.0。

**Spec:** `docs/superpowers/specs/2026-10-19-databridge-console-design.md`；`Codex.md`。

## File Map

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/databridge/task/TaskRepository.java` | 最近任务列表 |
| `backend/src/main/java/com/databridge/task/TaskProgress.java` | 持久检查点与事件积压读取 |
| `backend/src/main/java/com/databridge/task/TaskCatalog.java` | 双库连通性、表与列元数据读取 |
| `backend/src/main/java/com/databridge/task/TaskController.java` | 新增任务列表与进度路由 |
| `backend/src/main/java/com/databridge/task/TaskCatalogController.java` | 独立 `/api/catalog` 路由 |
| `backend/src/main/java/com/databridge/verify/VerificationRepository.java` | 最近报告读取 |
| `backend/src/main/java/com/databridge/verify/VerificationController.java` | 报告列表路由 |
| `backend/src/test/java/com/databridge/task/TaskApiIT.java` | 真实双库新 HTTP 路由测试 |
| `frontend/package.json`、`frontend/package-lock.json`、`frontend/vite.config.js` | 固定前端工具链与本机代理 |
| `frontend/src/api/client.js` | 类型安全以外的集中 fetch、错误码映射 |
| `frontend/src/App.vue`、`frontend/src/components/*.vue`、`frontend/src/styles.css` | 单页任务与校验工作台 |
| `frontend/tests/*.test.js` | API 与交互/状态测试 |
| `README.md` | 启动、演示、边界与测试命令 |

## Task 1：只读目录、任务列表和进度

- [x] 扩展 `TaskApiIT`，断言目录两库在线且包含 `api_items`，任务列表包含新任务，进度在首次全量后显示完成与事件积压、异常输入保持安全错误映射。
- [x] 运行 `mvn --batch-mode -q '-Dit.test=TaskApiIT' verify -Pintegration`，确认新增路由为 404。
- [x] 实现 `TaskCatalog`、`TaskProgress`、`TaskRepository.list()` 与控制器路由；目录单端失败只返回 `reachable:false`，不回传异常字符串。
- [x] 专项真实双库测试通过。

## Task 2：校验历史

- [x] 在 `TaskApiIT` 创建一份校验报告后，断言列表包含该 `runId`，未知任务 404。
- [x] 运行专项测试确认缺路由失败；实现 `VerificationRepository.listRecent(taskId)` 与 GET 列表接口，限制 20 条。
- [x] 专项测试通过，确认响应中没有业务字段原值或密码。

## Task 3：管理界面

- [x] 用真实已安装 Node.js 核实 Vite/Vue 所需运行版本，创建锁定依赖的 Vite/Vue 3/Element Plus/Vitest 项目；仅绑定本机，代理 `/api` 至 `127.0.0.1:18080`。
- [x] 先写 `frontend/tests/*.test.js`：错误码映射、状态标签、`INCONCLUSIVE` 展示、隐藏标签轮询停止；运行 `npm test` 确认失败。
- [x] 实现 `frontend/src/api/client.js`、Vue 任务表单/列表/进度/报告组件和工作台，遵循规范中的颜色、状态与无敏感值要求。
- [x] 运行 `npm test` 与 `npm run build`；以真实本机服务和浏览器完成端到端演示，验证窄屏、键盘焦点与无横向滚动。

## Task 4：交付

- [x] 在没有静态配置时确认 `GET http://127.0.0.1:18080/` 为 404；配置 Spring Boot 读取构建后的 `frontend/dist/`，确认根页与 `/assets/` 可从同一进程读取，不开放外网端口。
- [x] 更新 README 和规则状态，运行 `mvn --batch-mode -q clean verify -Pintegration`、`npm test`、`npm run build`、`git diff --check`。
- [x] 提交推送 `codex/console`，创建以 `codex/verification` 为 base 的堆叠 PR #7；记录未实现的正式 10 万/100 万行与故障/延迟三次实验，不把预实验冒充正式结果。

## 验证记录（2026-10-19）

- 后端 `mvn --batch-mode -q clean verify -Pintegration`：50 项，0 失败/错误/跳过；真实 MySQL 8.4.11 与 PostgreSQL 17.11 Testcontainers。
- 前端在 `npm ci` 后 `npm test`：9 项通过；`npm run build` 通过，入口 JS 174.00 kB、CSS 55.86 kB（压缩前）。官方 npm registry 的生产依赖 audit 为 0 个漏洞。
- 本机浏览器从创建任务到校验报告完成；源端更新经 CDC 到目标端可见；强制结束后端 Java 进程后，重启自动恢复 `STREAMING` 且两份报告仍可读取。
- 375px、768px、1440px 视口无横向溢出；375px 与 1440px axe 自动审计 0 个违规，装饰性箭头对比度留人工复核。
- 正式 10 万/100 万行、故障/延迟三次实验与论文正文仍是后续阶段，不把 1 万行单次预实验当成正式结果。
- 构建后由 Spring Boot 同源服务 `GET /` 与入口 JS `/assets/index-BsuD5NOP.js`，两者 HTTP 200；`GET /api/tasks` 仍正常返回。
