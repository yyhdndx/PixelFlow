# [Java][P1] 作品搜索接口（Android 前端已就绪）

负责人：WeiKnight（Gitee: @WeiKnight_CN）。状态：2026-10-31 新建，前端已交付，待后端实现。
`19` 是仓库任务编号，线上 Gitee Issue：https://gitee.com/yyhSEU/pixel-flow/issues/IKRZRU

对应验收：US-02 Feed 扩展 + 人机交互快捷性加分项；阻塞 Android 搜索页联调。
基线：`feature/search-frontend`（`9d86e19`）已含契约与 Android 调用方；合入后从最新 master 建分支。

## 背景

Android 搜索页前端已交付并合入待审分支：

- 首页顶部搜索入口 → `SearchActivity`：本地搜索历史（10 条、可清空）+ 静态热门词；
- 结果复用社区 Feed 的双列瀑布流卡片与详情页跳转，游标分页；
- 客户端已调用 `GET /api/search/posts?q=&count=&cursor=`，游客可用；
- 后端 404 时前端显示「搜索服务即将上线」占位，接口就绪后零改动生效。

契约已写入 `contracts/http/java-api.yaml` 的 `/api/search/posts`，以其为准。

## 工作范围

1. `GET /api/search/posts`：返回结构与 `/feed/` 完全一致（`status_code/has_more/next_cursor/post_list`，复用 `Post` schema 与现有 viewer-state 填充）。
2. 匹配规则：关键词匹配**标题与话题标签**前缀（大小写不敏感）；q 为 1–60 字，忽略首尾空白；空/超长返回 400 INVALID_ARGUMENT。
3. 可见性：仅返回 `PUBLISHED` 且作者未禁用/未注销的作品；游客可用（复用 Feed 的可见性逻辑，绝不返回草稿与待审核内容）。
4. 排序与分页：时间倒序 + 游标分页，cursor 语义与 `/feed/` 一致；count 1–50，默认 20。
5. **实现约束（4GB 演示服务器）**：MySQL `LIKE 'kw%'` 前缀匹配即可，给 `title` 建前缀索引；**禁止引入 Elasticsearch**；单查询必须有 LIMIT，避免全表扫描；不为搜索加缓存层（Redis 限流已覆盖滥用场景）。

## 实现边界与协作

- 只加只读接口，不新增表、不新增迁移；不改动 `/feed/` 现有行为。
- 热门搜索（前端目前是静态词）如后续要动态化，另立任务加只读 `hot` 接口，不在本任务内。
- Android 端如需字段微调，先改 `contracts/http/java-api.yaml` 再同步。
- 测试：至少覆盖——空 q/超长 q 400、无结果空列表、分页 cursor 正确、游客可见性与 Feed 一致（草稿/待审/撤回不可见）、作者禁用后从结果消失。MySQL 专项可复用 `MYSQL_TEST_*` 变量。

## 验收标准

- [ ] 契约与实现一致，OpenAPI yaml 与代码同步更新。
- [ ] 与 Android 搜索页真实联调通过：关键词命中、分页加载、空结果、游客访问。
- [ ] 无 ES 等额外组件依赖，查询在 MySQL 上走索引（EXPLAIN 前缀索引生效）。
- [ ] 普通 Feed 回归不受影响的证据（相关 JVM 测试通过）。
