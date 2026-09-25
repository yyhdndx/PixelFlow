# 跨服务验证

状态：测试目录骨架，不代表已有测试覆盖。

- `integration/`：Android/Web 使用的业务接口及 Java–Python 联调测试。
- `e2e/`：注册、发布、审核、推荐与反馈等完整场景。
- `fixtures/`：虚构用户、作品、行为及固定 Agent 评测样本。

模块单元测试分别放在 app/src/test、server/src/test、intelligence/tests 和 admin-web/tests。推荐评测同时记录数据版本、策略版本和执行参数。严禁将真实凭据或未经授权的用户数据作为样本提交。
