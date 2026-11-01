# Issue 22：管理员 Agent 轨迹查询接口验收

日期：2026-11-01。权威任务：[22-java-agent-trace查询接口](../issues/22-java-agent-trace查询接口.md)。

分支 `feature/issue-22-agent-trace-api`，基于 dev `8e1f858`；开发目录 `D:/desk/software_engineering/remote-progress-review`。原 pixel-flow 工作区的智能预审和未提交修改保持不动。

## 当前交付状态

Java 两个只读端点、OpenAPI、配置说明和 JVM 测试已实现。本次重新 fetch 后，origin/dev 仍是上述基线，没有 Python `/internal/traces*` 查询实现。因此当前是 Java 契约验证，不是实际 Python/云端/管理页面验收完成，不能关闭需要云端证据的任务验收项。

## 接口行为

- `GET /api/admin/agent/traces`：count 默认 20、范围 1–50；cursor 省略或正整数 long。支持前导零，转发时标准化。显式空值、负数、溢出、重复或未知参数返回 400。
- `GET /api/admin/agent/traces/{trace_id}`：UUID 格式；详情不存在映射为 404 TRACE_NOT_FOUND。
- 复用现有 ADMIN 权限规则：游客 401、普通用户 403；管理员可以跨用户审计，不允许普通用户以 user_id 参数扩大访问范围。
- 只向 Python `/internal/traces*` 发 GET，使用 `PIXELFLOW_INTELLIGENCE_TOKEN`。不转发浏览器令牌，不连接 analytics 数据库，不新增表或写入历史。
- 完整上游等待预算 10 秒，连接超时 2 秒，禁用重定向，响应最大 64 KiB；停止、连接失败、超时、上游 401/403/5xx 等均返回统一 502 AGENT_TRACE_UNAVAILABLE。
- 无效结构、字段类型、UUID、游标等返回 502 INVALID_AGENT_TRACE_RESPONSE；任何错误均不透传上游错误正文。
- 成功结果只输出契约白名单字段；列表问题按 Unicode 字符截断至 80 字，不截断 emoji。详情按原字段返回，sources 仅公开 post_id/title 两个审计字段。
- 过滤包括嵌套 llm、retrieval、sources 中的未知字段，不返回 api_key、raw_system_prompt、messages 等扩展内容。已知服务令牌若混入业务文本则拒绝响应。
- 不自动生成或补造缺失轨迹，Python 缺少必需字段时明确失败；成功响应 Cache-Control: no-store。

历史轨迹是管理员审计记录，不是用户公开搜索结果；展示历史 source 标识不改变作品详情的权限规则。白名单用于避免透传内部数据，并不意味着 Java 能判断任意用户问题/模型答案中的全部文本是否属于系统提示。

## Python 联调需对齐的约定

1. 列表响应为 `{traces: [...], next_cursor: null或数字字符串}`，可带 `status_code: 0`。按自增 id 倒序，下一页 `id < cursor`，不能按 UUID 或时间代替 id 排序。
2. 空数组时 next_cursor 必须为 null。非空下一游标应严格小于请求游标；Java 不自行排序、查询或计算数据库游标。
3. 摘要必含 trace_id/user_id/question/created_at/latency_ms/degraded/source_count/provider；Java 输出 question 最多 80 字。source_count 为 0–5。
4. 详情使用 `{trace: {...}}`（可附 status_code:0）；trace_id 须与路径匹配，session_id 为 UUID 或空字符串，retrieval 与 llm 字段必须存在；不存在返回 HTTP 404。
5. 时间是 Unix 秒或字段注明的毫秒；degraded 为整数 0/1；sources、selected_ids 最多 5 个，不返回密钥或系统提示。长度限制见 OpenAPI。
6. 对旧轨迹字段不足、未调用模型、失败轨迹等情况，由 Python 明确定义兼容输出；不要让 Java 伪造模型名、执行耗时或查询历史。

Python 字段如有调整，先更新契约，再同步 Java 与页面。当前 HTTP 替身严格依据任务书，不将其视为协调方已完成的实现。

## 测试

最终复验：67 项全部通过，0 失败、0 错误、0 跳过。AgentTraceApiTest 30 项、AgentTraceDeadlineTest 2 项、OpenApiContractTest 2 项；另有既有搜索 21 项和创作辅助 12 项回归。最终日志：server/issue22-verified.log。期间发现并修正 OpenAPI 组件插入位置的 YAML 缩进问题，修正后契约检查通过。

覆盖：游客/普通身份拒绝且不调用上游；管理员读取其他用户审计轨迹；服务令牌替换；默认值、分页、Unicode 截断；正常/空列表与详情；404；上游重定向、401/403/500/503；畸形/超大响应；无效字段与错误游标；详情 ID 不匹配；嵌套敏感字段过滤；缺配置、连接失败、响应头之后的慢正文超时。

超时测试用 150ms 测试预算快速验证完整正文等待，同时断言正式预算为 10 秒，不实际等待 10 秒。

专项命令（server 目录）：

```powershell
mvn -B '-Dtest=AgentTraceApiTest,AgentTraceDeadlineTest,OpenApiContractTest,AgentSearchApiTest,AgentSearchDeadlineTest,CreationAssistApiTest,CreationAssistProxyTest' test
```

若要同时执行既有任务21的真实 Python 搜索回归，需设置 ISSUE21_PYTHON 为装有仓库锁定依赖的解释器绝对路径；本次已启用。该测试验证的是已有搜索端点，不代替新轨迹查询的 Python 联调。

## 尚需完成的验收

- 协调方交付 Python 内部查询端点后，真实联调列表、详情、分页、服务令牌与不存在记录。
- 用实际 `/api/agent/search` 返回的 trace_id 查询对应轨迹，检查检索、模型、耗时、降级与来源。
- 管理端接线、云端配置/部署与页面验收由后续流程完成；本次没有修改页面或部署服务。
- 本次未访问生产 analytics 库、未读取真实凭据、未调用真实模型。
