# 推荐业务 Skill 契约

已实现五个可组合的 Python 业务 Skill 和注册表，版本均为 1.0.0。它们是项目运行时能力，不是 Codex 的个人技能文件，也不等同于已实现 Agent 或 MCP。

前三个（`profile_insight`、`candidate_recall`、`feed_rank`）服务于推荐链路；后两个（`content_safety`、`creator_assist`）服务于创作链路，只给建议、不改作品状态。

## 调用与 Schema

安装 intelligence 的依赖及本地包后，在 intelligence 目录执行：

```powershell
.\.venv\Scripts\python.exe scripts/demo_skills.py
.\.venv\Scripts\python.exe scripts/export_skill_catalog.py
```

第一条运行美食、数码、冷启动三组离线样例；第二条输出注册表元数据及完整输入、输出 JSON Schema。Schema 由实现模型生成，唯一入口为 `create_registry().describe()`，避免手写副本与代码漂移。

调用方式为 `registry.invoke(name, payload, context)`。成功返回 `skill`、`skill_version`、`trace_id`、`elapsed_ms` 和 `output`；多个步骤可以共用一个 Context、trace_id 和总截止时间。样例脚本提供完整可执行参数。

|名称|权限|主要输入|主要输出|
|---|---|---|---|
|profile_insight|profile:read|user_id、可选 snapshot、max_age_seconds|画像版本、策略、降级原因、归一化主题/作者/媒介偏好|
|candidate_recall|candidates:read|user_id、insight、candidates、excluded_post_ids、limit|候选及主题、可用召回渠道、检查/拒绝数量|
|feed_rank|candidates:read|user_id、insight、recalled、excluded_post_ids、limit、作者/主题上限|作品顺序、分数及分项、推荐原因、过滤数量、策略版本|
|content_safety|content:check|user_id、title、content、tags|risk（low/medium/high）、命中规则、修改建议、decision、策略版本|
|creator_assist|content:assist|user_id、title、content、media_count|主题、标题建议（≤3）、标签（≤5）、提示（≤6）、策略版本|

参数使用严格类型校验，拒绝未知字段、NaN 和无穷值。作品 ID 是正整数字符串，时间是 Unix 秒。snapshot 接受分析服务的画像字段；缺失可传 null。候选必须显式携带 status 和 author_enabled，不能省略后默认公开。

## 身份与数据边界

- Context 只能由可信服务根据已验证的用户身份、授权和服务器时间构造，不能接收客户端或模型自报的 scopes。这里的 scope 检查是进程内授权约束，不是 HTTP 身份认证实现。
- 输入 user_id、画像所属用户和 insight 所属用户必须与 Context 一致。将来 Agent 只能选择允许的 Skill 与业务参数，不能改写身份或扩大权限。
- Skill 不访问网络、数据库、文件或模型。候选是调用方提供的可信快照，不是从全站数据库实时召回；所谓热门仅表示输入候选池中的相对热度。
- excluded_post_ids 必须显式提供。可信适配层负责合并用户负反馈、屏蔽与业务排除项；空数组代表确实没有排除项，不能用来替代尚未获取的排除数据。
- Python 预览适配层读取本人持久化作品排除和作者推荐屏蔽列表，将当前池内被屏蔽作者的作品合并为排除项；关闭个性化时不向 Skill 传入兴趣画像。执行结束复核偏好版本，变化时整批丢弃结果。纯 Skill 核心仍不访问存储，也不把推荐屏蔽当成作品访问控制。
- Skill 仅检查快照。当前推荐预览适配层通过再次读取 Java 公开 Feed 复核公开状态及作者状态；正式推荐交付仍需完整的用户权限、屏蔽/负反馈检查，不能把预览当成正式推荐接口。

## 规则与限制

profile_insight 默认接受最近24小时的画像，可配置60秒至30天。缺失、冷启动、过期、超过服务器时间5分钟或无正向偏好时，返回 popular 策略及原因，且清空个性化偏好。有效画像合并重名项，按 score 重新计算 share，不信任传入占比；不修改原始画像。

candidate_recall 最多接收500个候选，输出1至100个（默认上限50）。仅保留 PUBLISHED、作者启用且创建时间不超过当前时间5分钟的候选，过滤排除项，去重；同 ID 快照冲突时整组拒绝。个性化召回先给兴趣匹配项最多 ceil(limit/2) 个名额，再由热门候选补齐。热门按点赞数、创建时间降序，最后按数字 ID 升序。channels 表示候选符合的渠道，不是实际执行轨迹。

feed_rank 最多接收100个召回项，重新计算主题，不信任上一步携带的 topics 或 channels，并重复硬过滤。分数为：

```text
4 × topic + 2 × author + media + popularity + freshness
popularity = min(1, log(1 + like_count) / log(1001))
freshness = 1 / (1 + age_days)
```

topic 是作品各主题对应偏好占比的均值；author、media 是对应偏好占比。popular 策略下三个偏好项均为0。分数相同按创建时间降序、数字 ID 升序，保证固定输入与时间下结果稳定。输出分数保留6位小数。规则版本为 feed-rank-v1，不代表已训练或验证推荐准确率。

返回数量上限默认10、最多50；每作者默认2条，每主题默认3条。多主题作品占用每个命中主题的名额，未知主题归为“其他”。无法满足数量时允许少返回，不放宽过滤和多样性限制。召回 rejected 统计硬过滤、重复及冲突，不统计容量截断；排序 filtered 同理，truncated_by_diversity 仅统计填满前遍历到的多样性拒绝项。

## 创作侧 Skill（content_safety / creator_assist）

两者都是**确定性规则**，不访问网络、数据库或模型，同一输入永远同一输出；它们只产出建议，不修改作品状态。

**content_safety**（scope `content:check`，策略版本 `content-safety-v1`）检查标题、正文与标签：

|规则|级别|触发条件|
|---|---|---|
|CONTACT_INFO|high|手机号、邮箱或“微信/加我/私聊”等站外联系方式|
|PRIVATE_TRADE|high|代购、刷单、返利、私下转账、定金等表述|
|ABUSIVE_LANGUAGE|high|人身攻击或侮辱性用语|
|EXTERNAL_LINK|medium|`http(s)://` 或 `www.` 站外链接|
|SPAM_REPEAT|medium|连续 10 个以上重复字符，或超过 20 个感叹号|
|LOW_INFORMATION|low|标题少于 4 字或正文少于 10 字|

输出 `risk` 取命中项的最高级别，并附 `suggestions` 与 `decision`：`allowed`（低风险）或 `human_review_required`（中高风险）。**该 Skill 不能下架、驳回或标记任何作品**，最终处置仍由管理员完成。

**creator_assist**（scope `content:assist`，策略版本 `creator-assist-v1`）复用平台 7 个主题关键词表：

- `topics`：命中的平台主题，未命中归为“其他”；
- `tags`：主题名 + 正文命中的关键词，最多 5 个，长度 ≤24；
- `title_suggestions`：最多 3 条、每条 ≤120 字；无标题时用首行生成，有标题时补主题前缀/后缀，不改写语义；
- `notes`：标题过长、未配图、缺少标签、正文过短等提示，最多 6 条。

它不调用大模型，因此不会“润色”出平台不认识的标签；客户端如需更自然的文案，可在拿到这些约束后自行生成。

## 失败与执行约束

注册表仅允许已注册名称，重名注册失败。SkillError 仅暴露 Skill 名称和稳定错误码，不返回内部异常文本或原始参数：

|错误码|含义|
|---|---|
|UNKNOWN_SKILL|名称未注册|
|FORBIDDEN|缺少身份或对应 scope|
|INVALID_INPUT|输入类型或字段不符合约束|
|IDENTITY_MISMATCH|参数中的用户与上下文不一致|
|TIMEOUT|超过 monotonic 截止时间|
|INVALID_OUTPUT|输出校验或用户归属不通过|
|EXECUTION_FAILED|处理器其他异常|

超时是协作式检查：调用前后和候选循环中检查截止时间，不是强制终止线程的沙箱。禁止直接加入可能无限阻塞的外部调用；后续网络适配必须另外设置请求超时与取消机制。当前只返回 trace_id 与耗时，未持久化执行日志，没有自动重试。画像业务降级由 profile_insight 明确返回；鉴权、输入和执行错误不会被吞掉后伪装为成功。

测试见 intelligence/tests/test_skills.py。Skill 核心无 HTTP 端点；独立预览适配层提供 GET /recommendations/me/preview，见 [HTTP 契约](../http/python-analytics.md)，不改变 Android 首页 Feed。
