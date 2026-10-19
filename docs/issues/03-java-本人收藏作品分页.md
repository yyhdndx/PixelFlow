# [Java] 提供本人收藏作品的详情列表与游标分页

状态：待发布的 Gitee Issue 草稿；`03` 是本地任务顺序，不是线上编号。优先级 P2，可与任务 01、02、04 独立开发。

## 前端场景与当前缺口

后续 Android「我的收藏」需要一次请求获得可展示的作品卡片，而不是拿到 ID 后逐个请求详情。当前收藏页面尚未接入，这是一项供后续页面使用的接口任务，不代表页面已经存在。

Java 已支持 `PUT /api/interactions/favorites` 收藏/取消收藏，以及 `GET /api/interactions/favorites` 查询 ID。现有 ID 列表保持不变。本任务新增完整作品列表，复用已有数据，不重做收藏写入，也不改用户资料和统计接口。

相关实现：[InteractionController](../../server/src/main/java/com/pixelflow/server/interaction/InteractionController.java)、[PostService](../../server/src/main/java/com/pixelflow/server/content/PostService.java)。开发环境见 [上手指南](../后端开发上手指南.md)。

## 新接口约定

`GET /api/users/me/favorites?count=20&cursor=123`，需要现有用户 Bearer token。`count` 默认 20，范围 1～50；`cursor` 可省略，提供时必须为有效的正整数，范围不超过 Java long。错误类型、空值、越界或非正整数返回既有 HTTP 400 / INVALID_ARGUMENT。

目标账号取自认证上下文，不接收用于切换查询身份的用户 ID。HTTP 200 的结构沿用 Feed 分页形式，设置 `Cache-Control: no-store`：

```json
{
  "status_code": 0,
  "post_list": [],
  "has_more": 0,
  "next_cursor": null
}
```

- 非空时，post_list 每项保持现有 Feed 卡片字段及类型，包括 post_id、title、content、create_time、status、author、clips、media_ids、hashtag、music、like_count、is_liked；额外返回 `is_favorited: true`。
- 本人是否点赞应根据当前请求用户计算，不能为了复用卡片默认写成 false。
- `has_more` 沿用现有 Feed 的数字 0/1；next_cursor 是字符串或 null。存在下一页时使用本页最后一条作品的数字 ID 转成字符串；末页和空页为 null。
- 收藏同时包含图文和视频，首版不增加筛选参数。

## 排序、可见性和分页规则

1. 仅查询当前用户 kind=favorites 的关系。只返回 PUBLISHED 且作者账号仍存在并启用的作品。即使收藏者是作者或管理员，也不在此列表展示草稿、待审核、驳回或撤回作品。
2. 按**作品数字 ID 降序**排列，查询条件为 post.id < cursor。这是作品发布列表的近似顺序，不是收藏时间顺序；当前关系表无收藏时间，本任务不增加字段。
3. 在 SQL 中完成收藏归属、作品可见性、游标筛选后，再查询 count+1 条可见作品判定 has_more。不能先分页收藏 ID，再过滤隐藏作品，否则可能形成错误的空页或提前结束。
4. 取消收藏后，后续请求不再返回该作品；再次收藏不会改变作品 ID 排序位置。作品不可见时不自动删除收藏关系，恢复可见后可重新出现。
5. 不提供跨请求快照保证。翻页期间新增/恢复可见的作品可能需要下拉刷新才能看到；固定数据下翻页不得重复或漏项。
6. target_id 是字符串，连接作品时注意数字 ID 的语义和 MySQL 隐式类型转换。不要按字符串字典序排列（例如把 9 排在 10 前），也不要误匹配非作品类型的关系。

## 实现边界

- 新建独立 `FavoritePostsController` 和 `FavoritePostsQueryService`，建议放在 interaction 包；使用独立 `FavoritePostsApiTest` 与 MySQL 测试。
- 不修改任务 01 的 UserController，不修改任务 02 的 UserStatsController，不占用 Flyway 迁移编号。
- 可调用现有 PostService 的公开卡片映射方法；如果发现必须调整共享方法，先在 PR 说明原因和影响。默认保持其公开行为不变，在本任务服务内补齐本人点赞和收藏状态。
- 保持原来的 GET/PUT `/api/interactions/favorites` 响应和语义；不修改公共安全策略、认证或异常处理以绕过新接口问题。
- OpenAPI 只新增本任务路径、独立的 FavoritesPage/FavoritePost schema，可引用已有公共卡片定义，不改个人资料或统计 schema。

## 验收条件

- [ ] 没有收藏时返回空列表、has_more=0、next_cursor=null。
- [ ] A/B 账号收藏隔离；未认证、失效令牌无法读取，停用账号沿用现有鉴权规则。
- [ ] 返回完整卡片，中文、视频、作者信息和图片 URL 可供现有渲染逻辑使用；is_liked 与实际点赞一致，is_favorited 恒为 true。
- [ ] 覆盖数字 ID 9、10、100 的排序，count 的 1、50、0、51 边界，以及 cursor 非数字、负数、0、空值、long 溢出。
- [ ] 首/中/末页和恰好 count 条情况正确；固定数据连续翻页无重复、无遗漏。
- [ ] 多条收藏不可见时，仍正确填充分页并判断下一页；不存在作品的关系不泄露异常或导致分页提前结束。
- [ ] 撤回作品、停用作者、取消收藏后，新请求不再返回对应作品；恢复可见和重新收藏符合既定口径。
- [ ] 原收藏读写和 Feed 测试继续通过；新增 H2 与独立 MySQL 8.4 验证，不把跳过当作通过。
- [ ] PR 附分页调用示例、测试命令/结果，说明仅按作品 ID 排序；不携带真实凭据。

## 不在范围

按收藏时间排序、批量取消、收藏夹、搜索、作品统计、个人资料编辑、Android 页面、Python、Agent、数据库结构升级和云端部署。后端验收不代替 Android 设备验收。
