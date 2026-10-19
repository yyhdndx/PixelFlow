# [Java] 为 Android 个人主页提供作品、粉丝和关注统计

状态：待发布的 Gitee Issue 草稿。`02` 是本地任务顺序，不是线上编号。

建议由另一位后端同学负责，优先级 P1。与 [任务 01：个人资料读取与编辑](01-java-个人资料读取与编辑.md) 并行，不依赖其新增的 bio 字段或资料编辑接口。先阅读 [后端开发上手指南](../后端开发上手指南.md)。

## 前端需求

Android「我的」页面已经有作品数、粉丝数、关注数三个位置，目前由本地 UserDataManager 提供数据。用户登录后应看到当前账号的真实统计；退出、切换账号或请求失败时，不能把其他账号的数据或占位数字当作真实结果。

本任务只提供一次请求读取三个数字的后端接口。Android 接入及点击数字后的列表页面另行处理。

## 已有实现与缺口

- [ProfileFragment](../../app/src/main/java/com/PixelFlow/app/ButtonFragment/ProfileFragment.java) 中已有 tvPostsCount、tvFollowersCount、tvFollowingCount。
- Java 的 posts 表存储 author_id 和作品状态，users 表存储 enabled。
- [InteractionController](../../server/src/main/java/com/pixelflow/server/interaction/InteractionController.java) 已提供关注/取消关注写入，关系存储在 interactions，kind 为 `follows`，user_id 为关注者，target_id 为被关注者。
- 目前没有个人主页三个统计值的聚合读取接口。不能用返回最多 50 个 ID 的关注列表长度代替总数。

## 接口约定（待实现）

新增 `GET /api/users/me/stats`，使用现有 Bearer 用户认证；目标用户只能来自认证上下文，不接受 userId 切换统计对象。

HTTP 200 示例：

```json
{
  "user_id": "当前用户UUID",
  "post_count": 12,
  "follower_count": 34,
  "following_count": 8
}
```

返回三个非负整数（服务端按 long/COUNT 处理，不转成字符串），空统计返回 0；不返回 null、不额外套响应包装，设置 `Cache-Control: no-store`。

统一口径如下，直接写入契约和测试：

| 字段 | 计数规则 |
| --- | --- |
| post_count | 当前用户创作且状态为 PUBLISHED 的作品数；不包含草稿、待审核、驳回、撤回 |
| follower_count | kind=follows、target_id=当前用户，且关注者账号仍存在并启用的关系数 |
| following_count | kind=follows、user_id=当前用户，且被关注者账号仍存在并启用的关系数 |

首版显示的是“已发布作品数”，不是本人作品管理页的全部作品数。账号停用时，其关注关系对其他人的统计暂不计入；恢复启用后重新计入，除非关系已被取消。屏蔽作者、推荐偏好、点赞、收藏不改变上述口径。

未登录、过期/无效令牌和停用账号沿用既有鉴权行为。数据库故障沿用现有错误处理，不能捕获后伪造三个 0。服务端不要求实时推送；客户端重新请求时读取当前已提交数据。

## 实现建议与并行边界

1. 在 users 包新增独立的 `UserStatsController`（需要时配合小型查询服务），不要把实现加进任务 01 正在修改的 UserController。
2. 复用现有 users、posts、interactions 查询，不增加缓存表、计数字段或触发器，本任务默认不新增 Flyway 迁移。
3. 使用参数化 SQL。可用一条包含三个 COUNT 子查询的 SELECT 保持单次读取一致性；避免先读 ID 列表再逐条查询，也不要从分页结果推导总数。
4. 使用独立 `UserStatsApiTest` 和 MySQL 集成测试，避免修改任务 01 的测试文件。测试覆盖真实数据库中的状态、关系变化，不只测试模拟返回值。
5. 更新 `contracts/http/java-api.yaml`，仅新增 `/api/users/me/stats` 及独立的 `UserStats` schema，不改任务 01 的个人资料 schema。

两人分别从当前 master 建分支：`feature/issue-01-user-profile`、`feature/issue-02-user-stats`。两项任务共享 users 包和 OpenAPI 文件，但实现类与 schema 分开。合并 OpenAPI 时保留双方新增项，不用整个文件覆盖；谁后合并，谁基于最新 master 再跑回归测试。

任务 01 预计新增 V7 的 bio 迁移，任务 02 无需占用迁移版本号。不要为本任务顺带改登录、关注写入规则或公共异常处理。

## 验收条件

- [ ] 新注册账号返回自己的 user_id，三个计数为 0，响应禁止缓存。
- [ ] 账号 A 与 B 的作品和关注数据相互隔离；客户端不能通过伪造用户 ID 查询别人统计。
- [ ] 作品仅计入 PUBLISHED；审核发布后增加、撤回后减少；重复请求不改变计数。
- [ ] A 关注 B 后，A 的 following_count 与 B 的 follower_count 各增加 1；取消后各减少 1。
- [ ] 重复关注/重复取消保持幂等；关注人数超过 50 时仍返回完整总数。
- [ ] 相关用户停用/重新启用时，关注统计按上述口径变化；不存在的目标账号不计入。
- [ ] 点赞、收藏、不感兴趣、屏蔽作者不会被误算成关注关系或作品数量。
- [ ] 匿名、无效或过期令牌不能读取统计；停用账号沿用现有鉴权规则。
- [ ] 接口查询失败返回错误而非伪造空统计；不返回密码哈希、令牌或其他用户明细。
- [ ] H2 快速测试和独立 MySQL 8.4 测试通过，报告不能用“跳过”代替数据库验收。
- [ ] PR 附一次成功响应、一次鉴权失败示例、测试命令及结果；既有关注写入接口行为不变。

## Android 后续接入约定

登录后请求统计，按响应刷新三个数字；刷新失败保留明确的加载/失败状态，不能把失败解释成 0。切换账号时先清空旧统计，校验响应 user_id 并丢弃迟到响应。作品数量使用“已发布作品”的口径。该部分不要求本 issue 的后端开发者修改 Android。

## 不在范围

个人资料编辑、头像上传、粉丝/关注列表、本人作品分页、评论、推荐或画像统计、Python、Agent/Skill/MCP、Web，以及云服务器发布。完成接口测试不等于完成 Android 真机验收。
