# Issue 21：Java Agent 智能搜索接口验收

日期：2026-11-01。权威任务：[21-java-agent-搜索接口](../issues/21-java-agent-搜索接口.md)。

开发分支：`feature/issue-21-agent-search-api`，基于 `origin/dev` 的 `a1f5dd3`。开发目录为 `D:/desk/software_engineering/remote-progress-review`。原 `pixel-flow` 工作区的智能预审、迁移和其他未提交内容未改动。

## 实现范围

新增 `POST /api/agent/search`，沿用现有登录鉴权及 `pixelflow.intelligence-base-url`。Java 仅转发请求、校验上游返回与来源可见性，不负责检索或总结，不新增数据表，不在 Java 保存问题、回答或会话历史，不改普通搜索和 Android。

- question 必填、非全空白，按 Unicode 字符计数最多 200；session_id 可省略、null、空字符串或 UUID。
- 透传当前登录用户的 Bearer 令牌；不接受客户端指定 user_id。Python 返回的 user_id 必须与登录用户一致。
- 上游完整响应等待预算 55 秒，连接超时 2 秒，不跟随重定向；响应体最多 64 KiB。预算覆盖响应头之后的慢速响应体，不仅是建立连接。
- 配置缺失、连接失败、超时或非 200 上游状态统一为 502 AGENT_SEARCH_UNAVAILABLE；无效报告或失效来源为 502 INVALID_AGENT_SEARCH_RESPONSE，使用既有统一错误结构。
- 成功响应仅含 status_code、answer、sources、session_id、trace_id、degraded，Cache-Control 为 no-store，不暴露内部 user_id 或 steps。
- 最多 5 个不重复的来源；逐一复核作品公开状态、作者启用状态、标题及摘录与当前文本的对应关系，避免失效或错误来源继续展示。
- 任一来源不可见时拒绝整份回答，不能仅过滤来源列表而保留可能包含该内容的回答。作者本人同样不能借接口引用自己的草稿。
- 检查引用编号对应 sources 范围；非空来源必须有引用。空来源统一返回“社区内暂未找到相关作品，可以换个说法再试。”，不透传无依据的空来源回答。
- OpenAPI 已同步请求、响应、401/400/502 和字段边界。

这些检查验证身份、结构、引用编号和作品来源，不代表 Java 能证明生成回答中的每句话语义都正确；事实准确性仍需真实模型评测。

## 本次实际测试

Windows、Java 22（按项目 release 17 编译）、Maven 3.9.9；Python 联调使用独立 Python 3.11 环境和仓库锁定依赖。

| 测试 | 通过 | 跳过 |
| --- | ---: | ---: |
| AgentSearchApiTest | 19 | 0 |
| AgentSearchDeadlineTest | 2 | 0 |
| CreationAssistApiTest | 9 | 0 |
| CreationAssistProxyTest | 3 | 0 |
| SearchPostsApiTest | 8 | 0 |
| OpenApiContractTest | 2 | 0 |
| 合计 | 43 | 0 |

覆盖请求校验、游客 401、身份绑定、会话透传、Unicode 边界、公开来源、草稿/待审核/驳回/撤回/删除/作者停用/内容修改后的拒绝、重复来源、错误引用、无效 JSON、超大响应、统一故障响应和普通搜索继续可用。

超时用例将测试预算缩短至 150ms，验证上游已返回响应头但迟迟未完成正文时仍按预算结束；同时断言正式预算为 55 秒。没有在测试中等待真实 55 秒。

## Python 联调说明

`actualPythonServiceNormalEmptyAndStopped` 启动真实 FastAPI 应用和临时 SQLite 分析库，Python 使用真实 JavaClient，通过 HTTP 请求本次 Spring 测试服务的身份和公开 Feed。Java 请求入口使用 MockMvc；测试上游转发器将 Java HTTP 请求送至真实 Python 进程。

仅将付费 DeepSeek 调用替换成依据实际来源摘录生成的固定回答。没有替换 Python 检索、用户认证、Skill 编排、trace/会话保存或 Java 可见性检查。

已通过：

1. 正常问题：返回来源、带引用的回答、会话与追踪标识，degraded=0。
2. 无匹配问题：sources 为空，Java 返回明确无结果提示。
3. Python 进程停止：接口返回统一 502，普通作品搜索仍为 200。另有直接连接关闭端口的测试覆盖 Java 连接失败。

临时 Python 进程在测试结束后关闭，测试数据位于系统临时目录，不访问生产数据库。

重跑：设置 `ISSUE21_PYTHON` 为已安装锁定依赖的 Python 解释器绝对路径，在 server 目录运行：

```powershell
$env:ISSUE21_PYTHON='你的测试Python解释器绝对路径'
mvn -B '-Dtest=AgentSearchApiTest,AgentSearchDeadlineTest,OpenApiContractTest,CreationAssistApiTest,CreationAssistProxyTest,SearchPostsApiTest' test
```

未设置 ISSUE21_PYTHON 时，仅真实 Python 进程联调用例跳过，不应算作已联调。本次已设置并执行通过。测试结果可在 server/target/surefire-reports 查看；本次日志为 server/issue21-focused.log。

## 验收边界与交接

- 四项任务验收标准已在本地通过；不代表远程 CI、线上部署或手机端验收完成。
- 未进行真实 DeepSeek 请求、模型质量评测、MySQL 集成专项、Android 设备测试。
- Android 现有 AI 请求读超时为 50 秒，短于 Java 要求的 55 秒；正常响应可直接使用已有卡片，接近预算上限时客户端可能先结束。端侧超时调整需由 Android 负责人协调，本次未修改。
- Python 既有分析库会记录 trace 和会话；“不记录搜索历史”在本任务中指 Java 不新增历史功能，不移除 Python 端已约定的执行追踪。
- Python 现有检索规模及全文/摘录策略不在此 Java issue 修改范围。
- 本次不合并到 dev/master、不推送、不部署；交给项目主导者按团队流程审查合入。
