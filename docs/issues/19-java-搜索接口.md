# [Java][P1] 作品搜索接口（Android 前端已就绪）

负责人：WeiKnight（Gitee: @WeiKnight_CN）。状态：2026-10-31 新建；2026-11-01 口径澄清 v2（实现以本文 v2 为准）。
`19` 是仓库任务编号，线上 Gitee Issue：https://gitee.com/yyhSEU/pixel-flow/issues/IKRZRU

对应验收：US-02 Feed 扩展 + 人机交互快捷性加分项；阻塞 Android 搜索页联调。
基线：`feature/search-frontend`（`9d86e19`）已含契约与 Android 调用方；合入后从最新 master 建分支。

## 口径澄清 v2（2026-11-01，与实现同学核对后修订）

针对初版任务书与 master 实际代码核对出的三处冲突，决议如下：

1. **索引与迁移的冲突——允许一个仅含索引的新迁移。**
   V1 迁移已执行，历史迁移不可改；为已有库交付 `title` 前缀索引必须通过新迁移。
   允许新增 `V12__search_title_index.sql`（内容只有一条 `ALTER TABLE posts ADD INDEX`，
   前缀长度 utf8mb4 下 30 字符，即 `title(30)`）。MySQL 与 H2 两个迁移目录都要有
   同版本文件，H2 提供等效标题索引。**迁移编号以 V12 为准**（V2 已被
   `V2__user_status_audit.sql` 占用，当前最新为 V11，合入前再次核对编号）。
   除此之外仍禁止任何表结构变更。
2. **「话题」无独立数据——定义为正文中的 `#话题名` 片段。**
   master 无标签字段/标签表，卡片 `hashtag` 固定为空数组，Android 将话题以文本形式追加进正文。
   匹配规则：大小写不敏感，命中两类位置之一即算匹配——
   (a) 标题前缀：`title LIKE 'kw%'`；(b) 正文话题：`content LIKE '%#kw%'`（用 `#` 锚定避免普通词误命中；
   q 若以 `#` 开头则去掉 `#` 后同样处理）。首版不拆词、不做分词，q 整体作为一个短语匹配。
   **索引限制**：title 前缀索引只加速 (a)，(b) 无法走索引，demo 数据量下靠 LIMIT + 小表扫描可接受；
   禁止为此引入全文索引/ES（4GB 约束不变）。验收必须对**包含 (a)(b) 两条件的最终完整 SQL** 做 EXPLAIN 并贴出，
   不允许只验证标题查询就宣称整个搜索走了索引。
3. **排序与游标——沿用 Feed 的数字 ID 倒序，不做按时间字段排序。**
   Feed 实际是 `ORDER BY id DESC` + `id < cursor`（PostService.java:126-131），契约里的
   「时间倒序」按「最新优先」理解：id 自增单调，ID 倒序等价于创建时间倒序，
   cursor 语义与 `/feed/` 完全一致（无定时发布等破坏单调性的场景）。OpenAPI 描述同步改为「最新优先」。

另有两项正常开发缺口（不是需求冲突，一并纳入本任务）：

- `SecurityConfig.java:34` 的 GET 放行名单需追加 `/api/search/posts`：不带 token 直接放行；
  带无效 token 时沿用现有鉴权过滤器规则（与 `/feed/` 行为一致）。
- OpenAPI 中 `/api/search/posts` 已定义但无实现——即本任务本身。

## 背景

Android 搜索页前端已交付并合入待审分支：

- 首页顶部搜索入口 → `SearchActivity`：本地搜索历史（10 条、可清空）+ 静态热门词；
- 结果复用社区 Feed 的双列瀑布流卡片与详情页跳转，游标分页；
- 客户端已调用 `GET /api/search/posts?q=&count=&cursor=`，游客可用；
- 后端 404 时前端显示「搜索服务即将上线」占位，接口就绪后零改动生效。

契约已写入 `contracts/http/java-api.yaml` 的 `/api/search/posts`，以其为准。

## 工作范围

1. `GET /api/search/posts`：返回结构与 `/feed/` 完全一致（`status_code/has_more/next_cursor/post_list`，复用 `Post` schema 与现有 viewer-state 填充）。
2. 匹配规则（v2）：大小写不敏感，命中任一类即匹配——(a) 标题前缀 `title LIKE 'kw%'`；(b) 正文话题 `content LIKE '%#kw%'`（`#` 锚定；q 以 `#` 开头则去掉后处理）；q 为 1–60 字，忽略首尾空白，整体短语不拆词；空/超长返回 400 INVALID_ARGUMENT。
3. 可见性：仅返回 `PUBLISHED` 且作者未禁用/未注销的作品；游客可用（复用 Feed 的可见性逻辑，绝不返回草稿与待审核内容）。
4. 排序与分页（v2）：`ORDER BY id DESC` + `id < cursor`（与 `/feed/` 完全一致的游标语义）；count 1–50，默认 20。
5. **实现约束（4GB 演示服务器）**：匹配用 `LIKE` 即可；允许新增仅含索引的迁移 `V12__search_title_index.sql`（`title(30)` 前缀索引，MySQL 与 H2 两目录同版本，H2 等效索引）；**禁止引入 Elasticsearch / 全文索引**；单查询必须有 LIMIT；不为搜索加缓存层（Redis 限流已覆盖滥用场景）；对最终完整 SQL（含标题与正文话题两条件）做 EXPLAIN 并贴进 PR。

## 实现边界与协作

- 只加只读接口与一条索引迁移（V12，仅 ALTER INDEX），不新增表、不改其他迁移；不改动 `/feed/` 现有行为。
- `SecurityConfig` 为 `GET /api/search/posts` 放行游客（不带 token 放行；无效 token 沿用现有规则）。
- 热门搜索（前端目前是静态词）如后续要动态化，另立任务加只读 `hot` 接口，不在本任务内。
- Android 端如需字段微调，先改 `contracts/http/java-api.yaml` 再同步。
- 测试：至少覆盖——空 q/超长 q 400、无结果空列表、分页 cursor 正确、游客可见性与 Feed 一致（草稿/待审/撤回不可见）、作者禁用后从结果消失。MySQL 专项可复用 `MYSQL_TEST_*` 变量。

## 验收标准

- [ ] 契约与实现一致，OpenAPI yaml 与代码同步更新。
- [ ] 与 Android 搜索页真实联调通过：关键词命中、分页加载、空结果、游客访问。
- [ ] 无 ES 等额外组件依赖；最终完整 SQL（标题+正文话题两条件）的 EXPLAIN 结果附在 PR 中，标题前缀索引生效、正文话题条件的扫描代价在 demo 数据量下可接受。
- [ ] 普通 Feed 回归不受影响的证据（相关 JVM 测试通过）。
