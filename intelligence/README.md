# Python 数据分析与智能服务

状态：目录骨架，尚未实现 HTTP 服务、Agent 或模型调用。拟采用 FastAPI 和轻量数据分析库，实际版本及依赖文件在实现阶段确定。

源码采用 `src/pixelflow_intelligence/` 布局。

|目录|职责|
|---|---|
|api|内部 HTTP 路由、输入输出校验|
|analytics|行为清洗聚合、用户画像和内容特征|
|recommendation|召回、排序、去重及热门策略|
|agents|目标解析、计划、Skill 选择、执行状态和结果整合|
|skills|项目业务 Skill、注册表、输入输出约束|
|mcp|MCP Server、工具定义及 Java/Python 接口适配|
|evaluation|策略与 Agent 的固定测试集评测|
|clients|Java 服务和模型提供方客户端|
|common|配置、日志、错误和 traceId|

Skill 子目录预留 profile_insight、candidate_recall、feed_rank、content_safety、creator_assist。注册表尚未实现；目录数量不代表已完成 Skill。

单元测试位于 `tests/`，公共评测样本位于仓库根目录 `tests/fixtures/`。业务 Skill 的输入、输出和权限约束记录在 `contracts/skills/`。实现 MCP 时复用身份和权限校验，不允许模型直接控制数据库写操作。
