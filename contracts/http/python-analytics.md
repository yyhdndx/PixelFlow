# Python 分析与推荐预览契约 v0.1

默认 http://127.0.0.1:8001。JSON，所有时间为 Unix 秒；画像只作兴趣分析，不是推荐结果。无模型调用或 Agent。OpenAPI 文档由运行服务的 /docs、/openapi.json 提供。

## GET /health

无认证，200：{"status":"UP"}。只表示进程存活，不表示 Java 可用或同步已追上。

## POST /internal/sync

运维状态见下方 GET /internal/status；/health 不访问数据库或 Java。

使用 Authorization: Bearer <PIXELFLOW_INTELLIGENCE_TOKEN>，无请求体。该密钥独立于 Java 导出密钥和用户 JWT。调用方是受控运维/调度进程，不是 Android。未配置密钥返回 503；缺失/错误返回 401。

每次从 Java GET /api/internal/analytics/events?after={cursor}&count=50 拉取一页。200 示例：

```json
{"imported":3,"updated_users":1,"next_cursor":3,"has_more":false}
```

has_more=true 时继续调用；即使 false，未来新增事件后仍需再次同步。事件、画像快照、游标在一个 SQLite 事务中提交。源身份变化、逆序、重复键冲突、无效游标、字段错误或存储错误返回 502，整页回滚；Java 超时/不可用/服务认证失败返回 503，游标不变。错误不回显上游响应、密钥和数据库异常。

## GET /internal/status

使用 PIXELFLOW_INTELLIGENCE_TOKEN，返回本地分析库汇总，无请求体。普通用户/管理员 JWT、Java 导出密钥不能访问；凭据缺失或无效返回401，未配置服务密钥或数据库/画像数据异常返回503。响应包含 Cache-Control: no-store，错误不回显数据库路径或原始数据。

|字段|含义|
|---|---|
|evaluated_at|状态统计时的 Unix 秒|
|source_bound / event_cursor|是否已绑定事件来源、最后提交的事件游标；不返回来源 ID|
|event_count|已存储事件总数，不是今天的事件数|
|profile_users / profile_snapshots|已有画像人数及所有历史画像版本数；不是 Java 注册用户总数|
|cold_start_users|各用户最新画像中 cold_start=true 的人数|
|stale_profile_users|最新画像达到24小时、生成时间超前超过5分钟或规则版本不一致的人数|
|profile_max_age_seconds|当前统计阈值，固定86400，不随某次维护请求的参数变化|
|oldest_profile_at / newest_profile_at|仅在各用户最新版本之间取最早/最晚生成时间，无画像时为 null|
|sync / refresh|最近一次成功提交的同步页/重算页：last_success_at、last_page_has_more；无记录为 null|
|upstream_backlog|固定 unknown：本接口不请求 Java，不能判断当前上游积压|

统计来自一个 SQLite 只读事务快照，不修改事件游标、画像或偏好。不返回用户 ID、标题、画像内容、令牌或数据库路径。新安装及从旧库升级后，成功记录可能为 null，即使已有历史数据也不能推断过去的执行时间。空同步页和未更新画像的重算页成功时也记录时间；失败整页回滚，保留上次成功记录，不记录最近失败原因。

成功记录与业务页同事务提交。last_success_at 为该页操作使用的服务器时间，不是精确的提交完成时间；refresh 的记录只描述最近一页，不能证明所有用户均已扫描。last_page_has_more=false 仅描述该次读取，不能证明现在没有新事件。空库返回200及零计数，不代表上游已同步。此接口会统计表和扫描每个用户的最新画像，适合课程规模的低频运维查询，不作为高频存活探针。

## POST /internal/profiles/refresh

运维批量重算接口，使用与 /internal/sync 相同的 PIXELFLOW_INTELLIGENCE_TOKEN。该服务密钥现在同时授权事件同步和画像维护；Java 导出密钥、管理员或普通用户 JWT 均不能替代。未配置返回503，凭据错误返回401。

请求体必填，可传 `{}` 使用默认值：

```json
{"after":"","count":50,"max_age_seconds":86400}
```

after 是上一页的用户 ID 游标（最多36字符），不是事件序号；count 为1至100，max_age_seconds 为3600至2592000。严格校验类型及未知字段，错误返回422。不接受调用方指定服务器时间。

200 示例：

```json
{"scanned_users":50,"updated_users":12,"next_cursor":"最后扫描的用户UUID","has_more":true,"evaluated_at":1791000000}
```

按用户 ID 升序扫描已有画像，每页至多 count 人。画像达到指定年龄、规则版本不一致或生成时间超前超过5分钟时，根据已导入的事件重新计算最近30天兴趣，生成新版本。刚更新的用户只扫描不重算；没有画像的用户仍在查询时返回冷启动版本0。没有新事件也能使过期兴趣退出窗口。

has_more=true 时用 next_cursor 继续；完整扫描结束后，下一轮从空 after 开始，不能永远沿用最后游标。扫描期间新出现且排序在已扫描游标前的用户由下一轮覆盖。重算与事件同步共享 SQLite 写锁，整页原子提交；失败返回503并回滚本页，用原 after 重试。已提交的前页不会回滚。重复重算新鲜页面不会增加版本。

重算不访问 Java、不拉取漏同步事件、不改变同步游标、不清理历史数据、不修改推荐偏好，也不能推断作品最新公开状态。建议先完成事件同步再重算。单页限制的是用户数，不是单用户历史事件数，大用户的重算仍可能较慢并阻塞写入。当前没有自动调度器，调用方需要定期触发；超过一小时后再次重算可能产生新版本，版本并非内容哈希。

响应（含错误）为 Cache-Control: no-store。画像版本因此可能由事件同步或维护重算递增。

## GET /profiles/me

使用 Java 登录获取的用户 access token；Python 调用 Java GET /api/users/me 校验身份与启用状态。没有接受 userId 的查询接口。返回 Cache-Control: no-store。服务密钥不能替代用户 JWT；令牌失效/账号停用返回 401，Java 身份服务不可用返回 503。

200 示例（历史累计事件经规则过滤后的快照）：

```json
{
  "user_id":"用户UUID",
  "version":1,
  "rule_version":"profile-rules-v1",
  "generated_at":1791000000,
  "window_days":30,
  "timezone":"UTC",
  "cold_start":false,
  "topics":[{"name":"美食","score":6.0,"share":1.0}],
  "authors":[{"name":"作者UUID","score":6.0,"share":1.0}],
  "media":[{"name":"image","score":6.0,"share":1.0}],
  "event_counts":{"CLICK":1,"DWELL":1,"LIKE":1},
  "active_hours":{"0":3},
  "explanation":"点击、停留与服务端有效点赞规则说明"
}
```

topics/authors/media 各最多10项，score 为非负规则分，share 为该维度总分占比（返回前10项时占比之和可能小于1）。active_hours 为 UTC 小时 → 事件数。event_counts 为30天窗口内原始事件统计，不等于最终有效互动状态。画像没有数据时 version=0、cold_start=true、偏好列表为空。

已有画像 version 在同步新事件或维护重算时递增，重复空同步页不变。已支持维护接口重算长期不活跃用户，但需要运维触发，尚无自动调度；generated_at 较旧时不能当作实时30天画像。Android 通过 Java /api/users/me/profile 查询，只展示版本和主题偏好。Python 已支持关闭推荐个性化，但尚未接入 Android，也不等同于删除画像或停止行为采集。

## 本人推荐偏好

推荐偏好已迁移至 Java（`GET/PUT /api/recommendations/me/*`），Java 为唯一写入来源。本服务只读代理：

- `GET /recommendations/me/preferences`：使用用户 access token，代理 Java 同名端点返回本人偏好快照。
- 旧写端点 `PUT /recommendations/me/preferences`、`PUT /recommendations/me/exclusions/{post_id}`、`PUT /recommendations/me/blocked-authors/{author_id}`：一律返回 410，不再本地落盘。请改用 Java 的 `/api/recommendations/me/preferences`、`/api/interactions/not-interested`、`/api/recommendations/me/blocked-authors/{author_id}`。

预览流水线（`GET /recommendations/me/preview`）通过上述 GET 端点读取 Java 权威偏好；单作品排除、作者屏蔽、个性化开关均来自 Java。

## GET /recommendations/me/preview

使用 Java 用户 access token，无请求体或业务参数，不接受调用方指定用户、画像、候选、scope 或 Skill 名称。用户身份由 Java /api/users/me 验证；即使传入 user_id 查询参数也不会切换身份。仅供受控开发联调，目前没有 Java 推荐代理或 Android 首页接入。

执行顺序：读取本人本地画像 → Java 公开 Feed 最近50条（含视频）→ profile_insight → candidate_recall → feed_rank → 再次读取 Java 公开 Feed。第二次读取中消失或任意候选字段变化的作品都从结果移除，不用未经复核的作品补位。不能使用作品详情代替公开 Feed 复核，因为作者和管理员有权查看非公开详情。

成功为200，Cache-Control: no-store。响应字段：

|字段|含义|
|---|---|
|user_id / trace_id|验证后的用户与本次调用追踪 ID|
|mode|固定 preview|
|strategy / reason|personalized 或 popular，及画像可用/降级原因|
|profile_version / policy_version|画像版本与 feed-rank-v1 规则版本|
|preferences_version / preferences_changed|本次使用的偏好版本，以及返回前版本是否发生变化|
|candidate_count|首次候选池大小，最多50|
|removed_on_recheck|复核时被移除的已排序作品数|
|items|最多10条：post_id、author_id、topics、score、components、reason|
|steps|三个 Skill 的名称、版本、共用 trace_id、各自执行耗时；不包含原始画像或令牌|
|limitations|固定列出 latest_50_public_posts、recommendation_only_blocking、snapshot_visibility|

无候选或全部被过滤时仍返回200和空 items。缺失、失效令牌或停用账号为401。Java 获取/复核失败、候选格式错误、Skill 错误、分析库异常或执行超时为503，不退回未经复核的缓存推荐。

这是确定性 Skill 流水线，不是 Agent：不调用模型、不动态规划、不写互动或曝光事件。每次请求独立计算，不自动同步分析事件。画像超过24小时会降级为热门策略。候选获取每次网络请求超时5秒；身份验证之后的流水线共享15秒协作式截止时间，不是整个 HTTP 请求的强制墙钟上限。steps 耗时不含网络获取及最终复核。

预览读取本人持久化排除列表，传入召回和排序 Skill；关闭个性化时 reason=personalization_disabled、profile_version=0，表示本次不使用画像，并不是删除了原画像。返回前再次读取偏好版本，若变化则设置 preferences_changed=true 并返回空 items，客户端可重新请求；steps 和版本仍描述本次旧快照的计算。偏好读取失败返回503，不忽略排除继续推荐。removed_on_recheck 包括公开候选复核和偏好版本变化导致的移除数。

边界：候选只覆盖最近50条，而非全库；作品“不感兴趣”和作者推荐屏蔽已接入。公开状态和偏好复核都是快照，不保证响应送达后状态不再改变；客户端打开作品仍需 Java 再次鉴权。高并发下因候选窗口、点赞或偏好变化导致少返回是有意的保守行为。正式推荐上线前仍需补齐 Java 交付层与客户端接入。

## 安全与兼容边界

服务仅在受控内网运行；生产需要 HTTPS、密钥轮换、请求限流和日志/数据保留策略。画像是行为与规则的推断，不推断身份、健康等敏感属性。旧 Java DWELL 事件没有 duration_ms 时只计事件次数，不计停留权重。旧客户端自报 LIKE 不增加点赞权重；取消点赞只移除 SERVER LIKE 的权重。
