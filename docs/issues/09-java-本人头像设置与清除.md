# [Java] 本人头像设置与清除

状态：已实现并完成本地验收（2026-10-21）；分支 `feature/user-avatar`，基于 master `6e9f05e`。尚未推送、合并或部署，目标环境 CI 待验证。仓库 Markdown issue；不占用同学负责的 Issue 07 作者作品分页。

## 交付目标

复用 JPEG/PNG 上传，允许本人选取已上传图片作为头像或恢复默认。保持现有资料、公开主页、Feed 作者及粉丝卡片的 avatar URL 字段兼容，不接受客户端随意提交外部 URL。

- `PUT /api/users/me/avatar`：Bearer 登录，请求 `{"mediaId":"本人上传返回的小写 UUID"}`；成功 200 `{"user_id":"本人ID","avatar":"图片URL"}`，no-store。
- `DELETE /api/users/me/avatar`：成功 200，同形状但 avatar 为空字符串；幂等，不物理删除图片，也适用于旧演示头像。客户端空 URL 使用默认图。
- 无效字段/类型/UUID 返回 400 INVALID_ARGUMENT；不存在或他人的媒体统一 404 MEDIA_NOT_FOUND；未登录/失效会话沿用 401。只能修改本人，管理员不能借此代改。
- 设置前图片保持既有私有权限；成为启用账号头像后，既有 `/api/media/{id}/content` 允许游客读取，仍 no-store。清除/更换/停用后，旧图片若无其他公开作品引用则重新私有；上传者和管理员保留原访问权限。不能收回已下载的副本。
- 本人当前头像计入媒体 referenceCount，不能删除，unusedOnly 不返回；停用用户头像仍算引用。替换/清除只释放头像引用，不删除旧图、不改变作品引用。旧图若无其他引用，可显式删除。
- 设置与媒体删除均先锁同一用户行，避免并发删图后产生悬空头像；新增外键作为完整性保护。

## 数据与部署

新增 H2/MySQL V8：users.avatar_media_id 可空外键指向 media.id，并建索引。保留 avatar URL；旧数据引用字段为 null，不猜测/解析旧 URL，不自动公开旧上传资源。V1–V7 不修改。

本次包含数据库迁移，自动部署会被现有保护拦截，须先备份、评审并人工发布，不能移除门禁。V8 后不支持直接退回旧应用写入：旧版不维护头像引用，可能出现 URL 与引用不一致；回滚必须单独评审。旧 H2→MySQL 导入工具仅支持 V6/V7 源，会拒绝 V8，不能用它迁移新库。

## 验收范围

真实上传→设置→本人/公开主页/Feed/粉丝资料读取；跨账号拒绝；幂等/非法输入；默认与旧头像清除；公开读权限随状态变化；头像和作品引用累计；删除保护与 unusedOnly；并发设置/删除；V7→V8 数据保留、重启与外键；H2 与独立 MySQL 测试及 CI。

不做裁剪、人脸识别、远程 URL 抓取、独立头像上传、视频头像或 Android 页面。上传仍遵循现有 8 MiB、400 万像素限制，头像裁剪/缩放由后续客户端实现。

## 测试运行

在 server 目录执行 `mvn test` 可运行 H2 套件。MySQL 集成测试增加两个独立变量：

- `MYSQL_TEST_AVATAR_URL`：如 `jdbc:mysql://127.0.0.1:3306/pf_avatar_test?useSSL=false&allowPublicKeyRetrieval=true`。
- `MYSQL_TEST_AVATAR_MIGRATION_URL`：如 `jdbc:mysql://127.0.0.1:3306/pf_avatar_migration_test?useSSL=false&allowPublicKeyRetrieval=true`，每次迁移验收须为空库，不能复用已经升级的库。

账号密码沿用 `MYSQL_TEST_USER` / `MYSQL_TEST_PASSWORD`，只在测试终端注入；所有库名必须 `_test` 后缀。CI 已配置各自的 MySQL 8.4 测试库。测试会清表，不能连接共享业务库；首次 H2→MySQL 的旧工具与本次 V7→V8 原地升级是不同流程。

## 本地验收结果

- `mvn -q verify` 成功并生成 JAR：161 项通过，0 跳过、0 失败、0 错误。
- 本次启用了全部 Java MySQL 套件，各使用独立测试库；头像 API 的 H2/MySQL 各 6 项通过，V7→V8 迁移的 H2/MySQL 各 1 项通过。包含真实上传与并发设置/删除，旧 V6/V7 H2 导入 MySQL 套件也通过。
- OpenAPI 契约/引用检查、actionlint 和 git diff 空白检查通过。
- 实际环境 Windows / Java 22（编译目标 17）/ 本机 MySQL 9.0.1，不能替代目标 Java 17 / MySQL 8.4 CI 验证；未连接云端业务库，测试实例已停机，测试库保留供复查。
- 未修改作者作品分页、Python、Android 或线上配置。主工作区原有未提交文件保留。
