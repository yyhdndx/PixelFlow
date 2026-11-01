# [Java][P2] posts 增加 submit_time / review_time / publish_time 时间字段

负责人：WeiKnight（Gitee: @WeiKnight_CN）。状态：2026-11-01 新建，待领取。
`20` 是仓库任务编号，线上 Gitee Issue：https://gitee.com/yyhSEU/pixel-flow/issues/IKS0WJ

前置：PR #14（Web 管理端）已合入 dev（`3f4e82b`），审核队列「提交时间」当前显示「—」，
本任务补齐数据侧；管理端展示侧改动另随管理端迭代小改（把「—」换成 submit_time）。

## 背景

`posts` 表现只有 `create_time`，表达不了「作品何时真正公开」。拆成四个时间点：

| 字段 | 含义 | 更新时机 |
| --- | --- | --- |
| `create_time` | 草稿创建时间 | 已有，建草稿时写入，之后不再变 |
| `submit_time` | 最近提交审核时间 | 作者 DRAFT/REJECTED/WITHDRAWN → PENDING |
| `review_time` | 最近审核时间 | 管理员 PENDING → PUBLISHED / REJECTED |
| `publish_time` | 发布时间 | PENDING → PUBLISHED |

四个字段均为「最近一次」语义；完整历史仍由 `moderation_records` 审计表保留。
撤回不重置 submit_time / publish_time；驳回后 submit_time 随再次提交更新。

## 改动范围

1. **迁移**：H2/MySQL 各新增 V12，posts 加三个可空 BIGINT 列；存量 PUBLISHED 作品
   `publish_time` 回填为 `create_time`，其余留 NULL。
2. **写入点**：
   - `PostService.transition`（publish=true → PENDING）：写 submit_time；
   - `ModerationController` 审核通过：review_time + publish_time；驳回：review_time。
3. **查询出口（勿漏）**：
   - `PostService.view()` —— /feed/、详情、我的作品、搜索自动带上；
   - `UserPostsQueryService`（作者主页）与 `FavoritePostsQueryService`（我的收藏）
     自行构造卡片、不经 view()，必须手动加；
   - `GET /api/admin/posts` 审核队列带 submit_time（管理端等待时长展示/排序）。
4. **契约**：Post schema 加三个 nullable 字段；`/api/admin/posts` 响应同步更新。
5. **展示兜底**：对外时间展示用 `COALESCE(publish_time, create_time)`，撤回作品保留原发布时间。
6. **可选**：MCP `list_my_posts` 增加 submit_time，Agent 可判断待审时长。

## 边界

- 不改 `/feed/` 排序与 cursor 语义；本期不加 update_time（最近编辑时间）。
- 不改 Python 侧；推荐预览为透传，联调时确认字段跟随即可。
- 不改动 `/api/admin/moderation` 审计记录结构。

## 验收标准

- [ ] V12 迁移 H2/MySQL 双库通过，存量回填正确。
- [ ] 驳回 → 再提交 → 通过全链路时间正确（JVM + MySQL 专项测试断言）。
- [ ] Feed/详情/作者主页/收藏/审核队列均带新字段，两个不走 view() 的查询服务不遗漏。
- [ ] 与管理端联调：审核队列显示提交时间与等待时长。
- [ ] 相关 JVM 测试通过，普通 Feed 回归不受影响。
