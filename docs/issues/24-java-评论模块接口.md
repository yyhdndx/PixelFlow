# [Java][P2] 评论模块：comments 表与发评/列表/删除接口

负责人：yyh（Gitee: @yyhSEU）。状态：2026-11-02 新建，待领取。
`24` 是仓库任务编号，线上 Gitee Issue：https://gitee.com/yyhSEU/pixel-flow/issues/IKS8US

前置：无硬前置；与任务 20（时间字段）、Agent 评论总结均解耦——本任务落地后，
「评论总结」Agent 功能才能从演示数据切换为真实数据链路（另行开任务，不在本范围）。

## 背景

当前全链路无评论能力：Android 详情页只有入口 UI 壳（输入框 + 按钮未接线），
Java 侧无 comments 表与接口，契约无定义。需求文档 6.1.3 要求详情页提供评论入口，
11.2 将「更完整的评论」列入后续迭代——本任务即补齐最小真实闭环。

## 改动范围

1. **迁移**：H2/MySQL 各新增一版（版本号以当时实际占用为准，勿覆盖历史文件），
   新建 `comments` 表，核心列：
   - `comment_id` BIGINT 主键（与 posts.post_id 同风格发号）
   - `post_id` BIGINT 非空，索引
   - `author_id` VARCHAR(36) 非空
   - `content` VARCHAR(500) 非空
   - `status` VARCHAR(16) 非空默认 `VISIBLE`（`VISIBLE` / `HIDDEN` / `DELETED`，软删）
   - `create_time` BIGINT 非空
   另给 `posts` 加冗余列 `comment_count` INT 非空默认 0，事务内维护。
2. **接口**（均写进 contracts/http/java-api.yaml）：
   - `POST /api/posts/{postId}/comments`：登录用户发评论；仅 PUBLISHED 作品可评；
     content 1–500 字、非空白；成功后返回完整评论对象，posts.comment_count +1。
   - `GET /api/posts/{postId}/comments?cursor=&count=`：登录可见；按 comment_id
     倒序游标分页（与现有游标分页语义一致）；只返回 VISIBLE；每页默认 20。
   - `DELETE /api/comments/{commentId}`：作者本人或 ADMIN；软删 status=DELETED，
     comment_count -1；重复删除幂等返回成功。
   - `PUT /api/admin/comments/{commentId}/status`：ADMIN 隐藏/恢复（HIDDEN/VISIBLE），
     隐藏时 comment_count -1、恢复 +1，供管理端评论管理用。
3. **计数一致性**：发帖/删评/管理员隐藏均事务内同步 posts.comment_count；
   详情接口（PostService.view）卡片带出 comment_count，Android 详情页与 Feed 展示用。
4. **约束**：评论即时可见，不进审核队列；发布时做基础校验（长度、空白、作品状态），
   违禁词拦截复用现有实现（若 Java 侧暂无则列为可选，不阻塞本任务）。

## 边界

- 不做楼中楼/回复关系、不做评论点赞、不做编辑；这些留待后续迭代。
- 不改 Python 侧；Agent 评论总结读取本表另开任务（本任务只需保证表结构与查询出口稳定）。
- 不改动现有审核（moderation）链路；评论管理在管理端为新页面，由协调方另做前端。
- 游客不可见评论列表（与详情页一致要求登录）。

## 验收标准

- [ ] H2/MySQL 迁移双库通过，comments 表与 comment_count 列就绪。
- [ ] 发评 → 列表可见 → 删除 → 列表消失全链路 JVM + MySQL 专项测试通过。
- [ ] 仅 PUBLISHED 作品可评论；非作者非管理员删除返回 403；游客 401。
- [ ] 游标分页边界正确（count/游标传参校验，与现有分页风格一致）。
- [ ] comment_count 在发评/删除/隐藏/恢复下均一致（测试断言）。
- [ ] 契约 yaml 与代码同步，相关 JVM 测试全部通过。
