# [Java][P1] Agent 轨迹查询接口（激活管理端「Agent 轨迹」占位页）

负责人：yyhSEU（Gitee: @yyhSEU）。状态：2026-11-01 Java 已在 feature/issue-22-agent-trace-api 实现，待 Python 联调与云端验收。
`22` 是仓库任务编号，线上 Gitee Issue：https://gitee.com/yyhSEU/pixel-flow/issues/IKS2NZ
前置：任务21（`/api/agent/search` 全链路）已合并 dev 并云端部署，E2E 通过。

## 背景

- Python 智能服务在每次 Agent 执行时已把完整轨迹写入 analytics 库（trace_id 唯一），
  Java 网关 `/api/agent/search` 响应中已透传 `trace_id`。
- admin-web（任务18）左侧导航的「Agent 轨迹」页（`pages/agent-traces/Traces.vue`）
  目前是占位文案，README 注明「等待 US-05 提供 Java 查询接口」——**本任务就是喂给它数据**。
- 验收硬指标「trace 可核查」依赖本接口：演示时可向老师展示任意一次 AI 回答的
  检索关键词、命中帖子、模型与耗时、是否降级。

## 契约（先写入 contracts/http/java-api.yaml）

仅 ADMIN（挂 `/api/admin/**`，复用现有 hasRole 配置，无需改 SecurityConfig）。

### 1) 轨迹列表

`GET /api/admin/agent/traces?count=20&cursor=<numeric id>`

```json
{
  "status_code": 0,
  "traces": [
    {
      "trace_id": "uuid",
      "user_id": "uuid",
      "question": "截断至 80 字",
      "created_at": 1793526000,
      "latency_ms": 2400,
      "degraded": 0,
      "source_count": 5,
      "provider": "deepseek"
    }
  ],
  "next_cursor": null
}
```

- 按自增 id 倒序（最新在前）；count 1–50；cursor 为正整数（`id < cursor`），
  与作品搜索接口（任务19）口径一致。
- traces 为空返回空数组 + next_cursor null，不得报错。

### 2) 轨迹详情

`GET /api/admin/agent/traces/{trace_id}`（uuid，404 统一错误结构）

```json
{
  "status_code": 0,
  "trace": {
    "trace_id": "uuid",
    "user_id": "uuid",
    "session_id": "uuid 或空",
    "question": "原始问题",
    "created_at": 1793526000,
    "latency_ms": 2400,
    "degraded": 0,
    "degraded_reason": "空字符串或原因",
    "provider": "deepseek",
    "retrieval": { "query": "检索关键词", "candidate_count": 20, "selected_ids": ["855", "893"] },
    "llm": { "model": "deepseek-flash", "latency_ms": 1800 },
    "answer": "完整回答",
    "sources": [ { "post_id": "855", "title": "..." } ]
  }
}
```

## 实现方式（沿用任务21先例）

- **Java 只做鉴权 + 转发**：登录 ADMIN 鉴权后把请求转发到 Python 内部查询端点，
  复用 `PIXELFLOW_INTELLIGENCE_TOKEN` 共享令牌（Python 侧校验，不接收浏览器直连）。
- Python 内部端点（`GET /internal/traces`、`GET /internal/traces/{trace_id}`）由
  协调方（本机）在联调前补齐并自测；字段以 Python 实现为准，联调时对齐。
- 禁止：Java 直接连 analytics 库；返回中携带任何密钥/原始系统提示。

## 工作范围

1. `contracts/http/java-api.yaml` 增加上述两个端点（含错误码与 schema）。
2. Java 端点：参数校验（count/cursor/trace_id 格式）→ ADMIN 鉴权 → 转发 Python
   → 超时 10s → 错误映射 502 统一结构。
3. JVM 测试：未登录 401、非 ADMIN 403、正常列表/详情、空列表、404、Python 故障 502。

## 实现边界与协作

- 只加只读端点，不新增表、不改 `/api/agent/search` 行为。
- admin-web 前端接线（Traces.vue）由协调方在本接口联调通过后完成，不在本任务范围。
- 若 Python 字段需微调，先改契约再同步两边。

## 验收标准

- [x] 契约与实现一致，OpenAPI yaml 与代码同步更新。
- [x] 越权验证：游客 401、普通用户 403；无任何途径读到他人视角外的数据。
- [ ] 云端部署后管理端「Agent 轨迹」页可查到真实 trace（用任务21联调产生的 trace_id 验证）。
- [x] 相关 JVM 测试通过。

Java 实现及测试范围见 [任务22验收记录](../testing/Issue22-Agent轨迹查询接口验收.md)。Python 查询端点与管理页云端验证尚未完成，不据本地替身测试关闭整项任务。
