# DataBridge 本机运维台设计

> 依据已确认的选题方案 v3 第 2、3、7 节及核心链路设计；在静默窗口校验阶段完成后实施。

## 边界和使用者

使用者是本机演示和调试迁移任务的人。只连接同机 Spring Boot API，不引入账号、远程数据源注册、数据库密码输入、浏览器凭据持久化、多任务并行启动或跨库强一致宣称。后端仍仅绑定 `127.0.0.1`；Vite 开发服务器也只绑定本机，反向代理 `/api`。目标表由使用者先在 PostgreSQL 创建，页面只解释这一前置条件。

## 信息架构

单页工作台：左侧为任务列表，顶部显示 MySQL/PostgreSQL 连通状态；右侧显示当前任务的源→目标路径、真实阶段、持久复制检查点和 CDC 积压、操作按钮、最近校验报告及最多 100 条差异样本。无任务时展示创建表单。桌面优先，375px 窄屏改为上下布局。隐藏标签停止轮询，重新可见时立即刷新；失败请求给出可读操作提示和安全错误码，不展示服务端原始异常。按钮在请求中禁用，危险的停止操作需确认。

`CREATED/CAPTURING/COPYING/REPLAYING/STREAMING/PAUSED/STOPPED/FAILED` 显示真实状态。复制进度显示 `last_id/high_watermark` 原值，不因主键稀疏计算百分比；CDC 显示捕获事件数、已应用事件数和待应用事件数。报告必须区分 `CONSISTENT`、`DIFFERENT`、`INCONCLUSIVE`；后者显示原因码中文解释，不渲染为成功色。页面不展示或存储业务行值、数据库密码和 JDBC URL。

## API 契约（新增）

- `GET /api/catalog`：读取预配置源库和目标库的连通状态、可选业务表及列元数据。响应只含 `reachable/schema/table/columns` 等非秘密信息；单端异常时只返回该端 `reachable:false` 和空表列表，不回传异常文本。源端只列配置的 MySQL database，目标端跳过系统与元数据 schema。
- `GET /api/tasks`：按创建时间倒序返回所有持久任务记录；不含连接串与密码。
- `GET /api/tasks/{taskId}/progress`：来自 PostgreSQL `full_checkpoint`、`cdc_event` 和 `event_cursor` 的快照，字段 `highWatermark`、`lastCopiedId`、`copyComplete`、`capturedEvents`、`appliedEvents`、`pendingEvents`；新任务缺检查点时前三者为 `null`。任务不存在返回 404。
- `GET /api/tasks/{taskId}/verifications`：按完成时间倒序返回最近 20 份已持久报告（含差异样本），只含已有的无原值字段。任务不存在返回 404。

现有 `POST /api/tasks`、`GET /api/tasks/{id}`、`POST .../start|pause|resume|stop`、`POST .../verifications`、`GET .../verifications/{runId}` 保持不变。前端只通过单个 `api/client.js` 发请求，错误响应映射为 `code` 与适合界面的固定中文说明。

## 视觉和交互

定位为“数据路径控制台”，不是营销站点：用可辨认的两库端点和贯穿的单条迁移路径作为主视觉，色彩编码只表达状态。深海军蓝 `#10233A` 用于导航，冷白 `#F6F8FA` 为工作区，青绿 `#0E8F86` 表示运行/一致，琥珀 `#B66A11` 表示等待/结论未定，朱红 `#C33C40` 表示失败/差异，正文 `#1C2C3D`。中文使用系统无衬线，ID 与计数使用系统等宽字体；不依赖在线字体。Element Plus 用于表单、提示和确认，页面布局及阶段路径由项目 CSS 自定，避免同样大小的卡片堆叠。焦点清晰、对比达标、按钮至少 44px、响应式不横向滚动；`prefers-reduced-motion` 禁用非必要动画。

## 验收

后端真实双库 HTTP 测试覆盖目录读取、任务列表、准确的持久进度、校验历史，确认响应无密码/行值。前端 Vitest 覆盖 API 错误映射、状态/报告标签和隐藏标签暂停轮询；`npm run build` 成功。浏览器演示从创建任务到校验报告完整走通，并保存截图和操作步骤。前端用 Node.js 24.18.0/npm 11.16.0；依赖由 `package-lock.json` 固定，运行和版本记录写入 README。
