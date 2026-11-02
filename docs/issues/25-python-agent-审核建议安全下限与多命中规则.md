# [Python][P0] Agent 审核建议安全下限与内容安全多命中修复

状态：2026-11-03 新建，本地审查发现。负责人：yyh（@yyhSEU）。

## 背景

对 Agent 现有能力（智能搜索、创作辅助、内容安全、审核建议、轨迹）做代码审查时，
发现两个 P0 级缺陷：审核建议的「安全下限」漏算模型风险等级、内容安全规则单命中漏报证据。
两者都会导致**高风险内容被误判为低风险**，属于安全逻辑漏洞。

## 问题

### P0-1 审核建议安全下限漏掉模型的风险等级

`intelligence/.../agents/moderation.py` 的 `suggest_moderation`：

```python
severity = max(ACTION_ORDER[rule_action], ACTION_ORDER[decision["recommended_action"]])
```

只取模型的 `recommended_action`，**完全不使用模型的 `risk_level`**。而
`intelligence/.../clients/deepseek.py` 的 `ModerationDecision` 只校验枚举、不校验
`risk_level` 与 `recommended_action` 必须对应。

后果：模型返回 `{"risk_level":"high","recommended_action":"approve"}`（矛盾但能通过校验），
规则层又判 `low` 时，最终 `max(approve, approve)=approve` → **risk=low / action=approve**，
模型亲口说"高风险"却被钳制成"通过"。违背该文件头注释「规则判 high 时最终建议不低于
reject 档位」的初衷——下限只防了规则、没防模型。

### P0-2 内容安全规则单命中，漏报证据

`intelligence/.../skills/content_safety/__init__.py`：

```python
if not hits and any(pattern.search(text) for pattern in _CONTACT_PATTERNS):
    hits.append("contact_info")
```

命中违禁词后 `hits` 非空，联系方式检测被**跳过**。一条同时含违禁词与微信号的内容
只报违禁词、漏报引流证据。文档《作品智能预审实施方案》§4.1 已明确此缺陷，当前仍未修。
审核建议链路实际走的正是此 `content_safety`，漏报会连带影响 `recommended_action` 下限。

## 修复方案

1. `ModerationDecision` 增加 `model_validator`，强制 `low↔approve / medium↔review / high↔reject`。
2. `suggest_moderation` 的安全下限改为取三者最高：
   `max(规则动作, 模型动作, 模型风险等级对应动作)`，并做防御（非法值回退 review）。
3. `content_safety` 去掉 `if not hits`，让违禁词与联系方式**独立检测、同时上报**。

## 边界

- 不改 Python 接口契约、不改 Java 侧、不改创作辅助输出结构。
- `ContentSafetyOutput.rule_hits` 本就支持多元素，多命中兼容现有调用方。
- 只做防御性钳制与证据补全，不新增自动通过/驳回/封号。

## 验收标准

- [ ] 模型返回 risk=high + action=approve（矛盾）时，最终建议不低于 reject。
- [ ] 模型返回 risk=high + action=reject 且规则 low 时，最终为 reject。
- [ ] 同时含违禁词与联系方式的内容，`rule_hits` 同时含 `banned_word` 与 `contact_info`。
- [ ] 相关 Python 单测全过（含新增矛盾/多命中用例）。
