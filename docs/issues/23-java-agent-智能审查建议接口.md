# [Java][P1] Agent 智能审查建议接口（admin-web 审核队列「AI 建议」）

负责人：yyhSEU（Gitee: @yyhSEU）。状态：2026-11-01 新建。
`23` 是仓库任务编号，线上 Gitee Issue：（发布后回填）
前置：任务21（`/api/agent/search` 网关先例）已合并部署；任务22（trace 查询）并行开发，
**本任务的 trace 落库与任务22共用同一套 analytics trace 机制与查询出口**。

## 背景

- 验收规划第一阶段第 ③ 条「智能审查」：`content_safety` 规则技能已在 Python 侧上线
  （违禁词表命中即高风险、联系方式/引流特征命中即中风险，输出来自 `content-safety-v1`），
  目前只被「AI 辅助创作」调用；审核队列侧还没有任何 AI 辅助。
- admin-web（任务18）审核队列页（`pages/moderation/Moderation.vue`）每个待审作品
  将展示「AI 建议」卡片（风险等级 + 建议通过/驳回 + 理由）。**只建议、不自动审核**，
  最终通过/驳回永远由管理员人工点击——这是验收演示的卖点，也是安全红线。
- 首版**不接 LLM**，纯规则引擎（`provider: "rules"`）：4GB 演示服务器零额外开销、
  毫秒级返回、结果确定可复现；LLM 辅助版留待后续版本。

## 契约（先写入 contracts/http/java-api.yaml）

仅 ADMIN（挂 `/api/admin/**`，复用现有 hasRole 配置，无需改 SecurityConfig）。

### 1) 请求审查建议

`POST /api/admin/agent/moderation-suggestion`

```json
{ "post_id": "855" }
```

### 2) 响应

```json
{
  "status_code": 0,
  "suggestion": {
    "suggestion_id": "uuid",
    "post_id": "855",
    "risk_level": "low",
    "recommended_action": "approve",
    "reason": "未命中任何规则，内容合规",
    "rule_hits": [],
    "suggestions": [],
    "policy_version": "content-safety-v1",
    "provider": "rules",
    "latency_ms": 12,
    "trace_id": "uuid"
  }
}
```

- `risk_level`：`low` / `medium` / `high`；`recommended_action`：`approve` / `review` / `reject`
  （规则映射：low→approve，medium→review，high→reject；映射逻辑在 Python 侧，Java 只透传）。
- `rule_hits` 为规则命中代码数组（如 `banned_word`、`contact_info`），可为空。
- `trace_id` 复用任务21的透传模式，写入 analytics trace 库，任务22的查询接口
  上线后可直接核查每一次审查建议。

### 3) 错误

- 未登录 401、非 ADMIN 403（现有机制）。
- `post_id` 不存在 404（统一错误结构）；Python 故障/超时 502 统一错误结构。

## 实现方式（沿用任务21/22先例）

- **Java 只做鉴权 + 取数 + 转发**：
  1. ADMIN 鉴权；
  2. 按 `post_id` 读取作品（标题/正文/标签，MySQL 业务库只由 Java 访问），
     不存在则 404；
  3. 将作品快照 POST 到 Python 内部端点
     `POST /internal/moderation-suggestion`（`PIXELFLOW_INTELLIGENCE_TOKEN` 共享令牌，
     不接收浏览器直连），超时预算 15s，错误映射 502。
- Python 内部端点与 trace 落库由协调方（本机）在联调前补齐并自测；
  字段以 Python 实现为准，联调时对齐。
- 禁止：Python 直连业务库；返回中携带任何密钥/原始系统提示。

## 工作范围

1. `contracts/http/java-api.yaml` 增加上述端点（含错误码与 schema）。
2. Java 端点：参数校验（post_id 非空数字串）→ ADMIN 鉴权 → 读取作品快照 →
   转发 Python → 超时 15s → 错误映射 502。
3. JVM 测试：未登录 401、非 ADMIN 403、正常三档风险（approve/review/reject）、
   post 不存在 404、Python 故障 502。

## 实现边界与协作

- 只加这一个端点，不新增表、不改现有审核通过/驳回链路（`moderation_records` 仍由人工审核写入）。
- admin-web 审核队列「AI 建议」卡片接线由协调方在本接口联调通过后完成，不在本任务范围。
- 与任务22对齐：trace 字段（question→审查对象摘要、provider、latency_ms、degraded=0）
  写入同一 trace 库，避免两套格式。
- 若 Python 字段需微调，先改契约再同步两边。

## 验收标准

- [ ] 契约与实现一致，OpenAPI yaml 与代码同步更新。
- [ ] 越权验证：游客 401、普通用户 403。
- [ ] 三档风险各跑一次真实作品：含违禁词作品建议 reject、含联系方式作品建议 review、
      正常作品建议 approve，reason 为中文可读。
- [ ] 每次调用产生可查询的 trace_id（任务22接口可查）。
- [ ] 相关 JVM 测试通过。
