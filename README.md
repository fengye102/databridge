# DataBridge

基于 Spring Boot 的 MySQL → PostgreSQL 全量迁移与增量同步毕业设计项目。

**当前阶段：设计待确认。** 核心链路采用“先捕获并持久化 CDC 事件、执行 JDBC 全量、再按序回放并持续同步”，避免迟到的全量批次覆盖新事件。当前仓库尚无可运行程序。

## 文档

- [项目规则](Codex.md)
- [选题方案 v3](docs/proposals/databridge-proposal-v3.md)
- [核心链路设计](docs/superpowers/specs/2026-10-18-databridge-core-design.md)
- [历史选题方案 v2](基于_Spring_Boot_的异构数据库迁移与增量同步平台的设计与实现_v2.md)

## 第一阶段范围

单机、单表、MySQL → PostgreSQL、单列不可变有符号 BIGINT 主键、预先建好的目标表。优先完成真实 CDC 捕获、可恢复全量迁移、事件回放和静默窗口下的一致性校验，再开发管理界面。

实施前请先阅读 `Codex.md` 与核心链路设计。后续会在设计确认后补充实施计划、Compose 环境、后端、测试和基础前端。
