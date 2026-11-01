# [Java][P1] Agent 智能搜索与多帖总结接口（Android AI 卡片待此接口）

负责人：yyhSEU（Gitee: @yyhSEU）。状态：2026-11-01 新建。
`21` 是仓库任务编号，线上 Gitee Issue：https://gitee.com/yyhSEU/pixel-flow/issues/IKS0Z3

对应验收：Agent 侧「智能搜索与多帖总结」主线；US-02 人机交互加分项。
基线：从最新 dev 建分支；Android 搜索页前端已上线 master（`feature/search-frontend`）。

## 背景

Agent 侧产品需求把「智能搜索与多帖总结」定为演示主线：用户用自然语言提问，
系统检索社区公开作品，综合 3–5 篇给出**带出处标注**的回答，来源可跳转原文。

- Android 侧（本机负责，并行开发）：搜索结果页顶部加「AI 综合回答」卡片
  （回答 + 来源列表 + 跳转详情），调用本接口；接口未就绪时显示降级文案。
- Python 智能服务侧：Agent 编排 + `search_posts` 检索 Skill + `/agent/search`
  端点，由本机 feature 分支并行实现，**本契约是两边联调的唯一依据**。
- 先例：创作辅助 `POST /api/agent/create-assist`（Java 鉴权后转发 Python，
  Android 读超时 50s），本接口完全沿用该模式。

检索规模已封顶（防"无限搜索"）：单轮检索 LIMIT 20 → 选 3–5 篇 → LLM 综合一次
→ 结束；整体超时 55s。追问是前端带 `session_id` 发新请求，不是服务端循环。

## 契约（先写入 contracts/http/java-api.yaml）

`POST /api/agent/search`，仅登录用户（复用 create-assist 的鉴权），游客 401。

请求：
```json
{"question": "用户问题，1–200 字", "session_id": "可选；首次为空，追问时带上首次响应返回的值"}
```

成功响应（`status_code: 0`）：
```json
{
  "status_code": 0,
  "answer": "中文综合回答，引用处以〔1〕〔2〕标注，对应 sources 下标",
  "sources": [
    {"post_id": "...", "title": "...", "excerpt": "正文前约 100 字", "author_name": "..."}
  ],
  "session_id": "uuid，追问时回传",
  "trace_id": "uuid，本次 Agent 执行的可核查 trace",
  "degraded": 0
}
```

约束：
- 仅综合 `PUBLISHED` 且对当前用户可见的作品，**绝不引用草稿/待审核/已删除内容**。
- `sources` 为空时 `answer` 必须明确说"社区内暂未找到相关作品"，不得编造。
- Python 侧不可用/超时：HTTP 502，body 为统一错误结构 + 明确 message；
  不得返回半截回答。前端据此显示"AI 总结暂不可用"，普通搜索列表不受影响。

## 工作范围

1. `contracts/http/java-api.yaml` 增加 `/api/agent/search`（含上面的请求/响应 schema 与错误码）。
2. Java 端点实现：登录鉴权 → 参数校验（question 1–200 字）→ 转发 Python
   `POST /agent/search`（路径/字段以 Python 侧实现为准，联调时对齐）→
   超时 55s → 错误映射（超时/连接失败 → 502 统一错误）。
3. 与 Python 分支联调通过三类场景：正常综合回答、零结果（sources 为空）、
   Python 宕机（502 降级）。

## 实现边界与协作

- 只加一个只读聚合端点，不新增表、不改 `/api/search/posts`（任务 19）行为。
- 首版不做流式输出、不做回答缓存、不记录搜索历史。
- Python 侧（检索 Skill、DeepSeek 综合、trace 落库）由本机并行开发；
   Java 侧只需按契约转发，不要自行实现检索或总结逻辑。
- Android 端字段如需微调，先改契约再同步两边。

## 验收标准

- [ ] 契约与实现一致，OpenAPI yaml 与代码同步更新。
- [ ] 三类场景联调通过：正常、零结果明确说明、Python 故障 502 且普通搜索不受影响。
- [ ] 越权验证：未登录 401；无任何途径让回答引用非 PUBLISHED 作品。
- [ ] 相关 JVM 测试通过（不影响 create-assist 现有行为）。
