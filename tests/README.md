# 跨服务验证

状态：本目录仍为组织骨架；可运行的 Java–Python 冒烟脚本位于 intelligence/scripts/smoke_pipeline.py，覆盖事件同步、画像变化、取消点赞、重复同步和本人画像代理。模块测试另见各模块目录，不将其等同于全量端到端验收。

- `integration/`：Android/Web 使用的业务接口及 Java–Python 联调测试。
- `e2e/`：注册、发布、审核、推荐与反馈等完整场景。
- `fixtures/`：虚构用户、作品、行为及固定 Agent 评测样本。

模块单元测试分别放在 app/src/test、server/src/test、intelligence/tests 和 admin-web/tests。推荐评测同时记录数据版本、策略版本和执行参数。严禁将真实凭据或未经授权的用户数据作为样本提交。

已实现纯规则推荐离线回归入口 `python -m pixelflow_intelligence.evaluation.recommendation`（使用 intelligence 虚拟环境）。九组虚构场景及指标边界见 [评测说明](../intelligence/src/pixelflow_intelligence/evaluation/README.md)，不是 Agent 评测或真实用户推荐准确率评估。
