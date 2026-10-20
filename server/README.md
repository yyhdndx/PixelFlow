# Java 核心业务服务

首次开发先按 [后端开发上手指南](../docs/后端开发上手指南.md)创建本地 MySQL 库，复制 [.env.example](.env.example) 并显式加载配置。本文用于模块实现和参数查询；云端状态见 [服务器部署记录](../deploy/服务器部署记录.md)。

独立 Maven 工程：Spring Boot 3.5.16、Java 17、Spring Security、Spring JDBC、Flyway。默认使用 MySQL，H2 仅用于离线测试和旧数据迁移。建库、配置和旧数据导入见 [MySQL 部署与迁移](../deploy/MySQL部署与迁移.md)。

源码根目录：`src/main/java/com/pixelflow/server/`。

|业务包|职责|
|---|---|
|auth|注册、登录、令牌、认证与 RBAC|
|users|用户资料、角色与账号状态|
|content|作品、媒体元数据、Feed 与发布|
|interaction|点赞、收藏、关注、负反馈与幂等处理|
|events|行为事件接收、去重与分析数据提供|
|moderation|人工审核、处理记录与管理操作|
|intelligence|调用 Python 服务、请求超时与普通 Feed 降级|
|common|统一错误、请求标识及必要公共配置|

## 启动

团队统一 JDK 17、Maven 3.6.3+。先按上手指南配置环境变量，再在仓库根目录执行：

```powershell
cd server
mvn test
mvn package
$env:PIXELFLOW_SEED='true'
# 先创建 pixelflow_app 数据库和同名账号，再通过私有环境注入 DB_PASSWORD。
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/pixelflow_app?connectionTimeZone=UTC&tinyInt1isBit=true'
$env:DB_USER='pixelflow_app'
java -jar target/pixelflow-server-0.1.0.jar
```

访问 `http://127.0.0.1:8080/health` 和 `http://127.0.0.1:8080/feed/?count=10&accept_video_clip=true`。种子开关在空库插入 6 个带插画头像的演示账号和 33 条图文/视频作品（含两个三图作品），使用随 JAR 打包的素材；按批次幂等执行，已有首批 24 条的库追加 9 条，已有前两批 30 条的库只追加 3 条。默认不创建管理员，不提供固定账号密码。旧版占位作品保持不动，详见[演示素材说明](../docs/server/演示素材说明.md)。

审核演示需启动前从私有配置注入 `PIXELFLOW_ADMIN_PASSWORD`，首次创建用户名 `admin` 的账号。密码至少 10 字符、UTF-8 不超过 72 字节；该变量不会重置已有账号。不要将真实密码保存到 Git、截图或共享终端历史。

## 配置

|变量|默认值|含义|
|---|---|---|
|SERVER_ADDRESS|127.0.0.1|默认仅本机；局域网联调可显式设为 0.0.0.0|
|SERVER_PORT|8080|HTTP 端口|
|DB_URL|jdbc:mysql://127.0.0.1:3306/pixelflow_app?connectionTimeZone=UTC&tinyInt1isBit=true|MySQL 业务库；远程连接需配置 TLS|
|DB_USER / DB_PASSWORD|pixelflow_app / 空|必须配置实际数据库账号密码|
|PIXELFLOW_JWT_SECRET|空|空时每次启动生成随机内存密钥；固定部署需注入至少 32 字节随机密钥|
|PIXELFLOW_SEED|false|首次插入演示作品|
|PIXELFLOW_PUBLIC_BASE_URL|http://127.0.0.1:8080|首次生成种子媒体 URL 使用的客户端可达地址|
|PIXELFLOW_ADMIN_PASSWORD|空|首次创建 admin；不输出到日志|
|PIXELFLOW_ANALYTICS_EXPORT_TOKEN|空|独立只读分析导出密钥，至少32字符；为空时内部导出全部拒绝|
|PIXELFLOW_INTELLIGENCE_BASE_URL|空|Java 访问 Python 的 origin，例如 http://127.0.0.1:8001；为空时画像查询返回503|

应用不自动读取 `.env`；变量由终端、IDE 或部署平台注入。未固定 JWT 密钥时重启会使旧 access token 失效，但数据库中未过期的 refresh token 仍可换新令牌。`runtime-data/` 已忽略，请单独备份，不要当构建产物删除。

Flyway 自动选择 `src/main/resources/db/mysql/` 或 `db/h2/`。H2 历史脚本仅移动路径，内容及校验和保留；MySQL 独立维护 V1–V7；V7 为旧账号补充默认空简介，头像沿用 V4。新增结构修改必须追加版本，不能改已执行的脚本。H2 的 PostgreSQL 模式只是测试兼容选项，不代表部署 PostgreSQL。

## 本人资料

登录后 `GET /api/users/me` 返回原有身份字段及非 null 的 `bio`、`avatar`，响应禁止缓存。`PUT /api/users/me` 仅完整替换昵称和简介；两字段均必填，昵称 trim 后为 1–40 个 UTF-16 单元，简介原样保存、最多 60 个 UTF-16 单元。未知字段或非法输入返回 400 `INVALID_ARGUMENT`，不修改头像和角色。

```bash
curl -H "Authorization: Bearer <access-token>" http://127.0.0.1:8080/api/users/me
curl -X PUT -H "Authorization: Bearer <access-token>" -H "Content-Type: application/json" \
  -d '{"nickname":"小明","bio":"摄影与旅行"}' http://127.0.0.1:8080/api/users/me
```

本地验收可在独立测试库启动服务，注册并登录后按上述顺序 GET、PUT、重新登录再 GET；不要在日常业务库试验迁移。

## Android 联调

### 本人粉丝与关注列表

登录后调用 `GET /api/users/me/followers?count=20` 或 `GET /api/users/me/following?count=20`。响应包含 `status_code`、`user_list`、数值型 `has_more`（0/1）和 `next_cursor`。用户卡片仅包含 `user_id`、`nickname`、`bio`、`avatar`、`is_following`；粉丝列表的 `is_following` 表示是否回关。关注/取消仍调用原有互动接口。

下一页传 `cursor=<next_cursor>`，末页游标为 null；第一页省略 cursor，不能传空字符串。count 范围 1–50。按用户 ID 字符串升序，不是关注时间排序；账号停用和关系变化可能改变结果，刷新时从第一页开始。不存在/停用账号在分页前过滤，不受推荐屏蔽偏好影响。接口只认登录身份，不接受查询其他用户；不新增表和迁移。

验收与独立 MySQL 测试方式见 [issue 05](../docs/issues/05-java-本人粉丝与关注分页.md)。

### 查看作者或其他用户主页

`GET /api/users/{id}/profile` 需要 Bearer 登录身份，返回 `user_id`、`nickname`、`bio`、`avatar`、`post_count`、`follower_count`、`following_count` 和 `is_following`。最后一个字段表示访问者是否关注此用户，不是反向关系。仅返回启用账号；不存在和停用均为 404。只提供公开资料，不含用户名、角色、草稿或收藏；作品列表不在本接口内。资料统计口径与本人统计一致，响应禁止缓存；本人资料编辑仍使用 `/api/users/me`。

验收与复测见 [issue 06](../docs/issues/06-java-用户主页公开资料.md)。

### 设备连接

模拟器访问宿主机使用 `http://10.0.2.2:8080`；首次生成种子数据前将 `PIXELFLOW_PUBLIC_BASE_URL` 设为这个地址。真机使用电脑局域网 IP，并显式配置监听地址和防火墙，不要公开暴露开发服务。

在 `app/src/main/assets/api.properties` 设置基础 origin `url`，不含路径或 `/feed/` 后缀，默认使用模拟器地址 http://10.0.2.2:8080。Android 已接入账号页、内存会话与令牌刷新、next_cursor 分页和首页/详情点赞；关注、收藏、发布及资料同步仍待接入。启动与设备验收见 [Android 联调说明](../docs/android/Java服务联调说明.md)。

已有种子作品的 URL 不会随环境变量变更自动更新，应显式编辑对应数据或使用独立演示数据库，勿误删业务数据。

## 文档与测试

- [实现设计与剩余任务](../docs/server/服务端实现说明.md)
- [OpenAPI 3.0 契约](../contracts/http/java-api.yaml)
- [社区图片、头像与视频素材及许可](../docs/server/演示素材说明.md)
- Flyway 数据库迁移：`src/main/resources/db/migration/`，启动自动执行。
- 测试：`src/test/java/com/pixelflow/server/`，`mvn test` 使用独立内存数据库，不修改本地业务数据。

此版本用于本地课程开发，不是可直接公开部署的生产服务。Python 已通过独立只读身份同步事件并生成规则画像；Java intelligence 包提供本人画像查询代理（3秒请求超时、身份交叉校验、不跟随重定向），推荐网关和 Agent 尚未实现。见 [Python 运行说明](../intelligence/README.md)。

## 图片上传与草稿（2026-10-03）

登录后使用 `POST /api/media`，`multipart/form-data` 的文件字段为 `file`。支持 JPEG/PNG，输入/转换后文件均最多 8 MiB，最多 400 万像素；内容识别并重新编码为 PNG。视频上传未开放。

上传响应包含 `mediaId`，用它创建草稿：

```json
{"title":"我的作品","content":"正文","mediaIds":["上传返回的 mediaId"]}
```

也可先用 `{"title":"","content":"未完成正文"}` 保存无媒体草稿，再 `PUT /api/posts/{id}` 完整替换。提交审核要求标题非空白且至少一个媒体；未完成草稿返回 409，不丢内容。

不要将上传响应的 URL 放进 `clips`；上传归属使用 `mediaIds` 校验。编辑时根据详情 `media_ids` 回填请求 `mediaIds`；遗漏媒体字段代表清空附件，PUT 不是局部合并。外部 URL 的 clips 接口仍保留，但不能与非空 mediaIds 混用。

图片与元数据当前同存数据库，V3 迁移自动建表；无需另建上传目录。每用户最多 100 张，未引用图片可由本人手动删除，自动过期清理尚未实现。该方案用于小规模课程演示，后续迁移对象存储；文件库备份包含图片。`PIXELFLOW_PUBLIC_BASE_URL` 同时用于生成上传图片地址，不使用请求 Host 头。它须为无路径/查询/凭据的 HTTP(S) origin；模拟器、真机地址要按上文配置。

未公开图片只能由上传者或管理员带 Bearer 令牌访问；作品公开后游客可访问。下架/停用作者后重新鉴权，响应禁止缓存，但无法收回用户此前已下载的副本。一张图关联多个作品时，只要仍有一个公开作品就保持公开可读。

## 上传图片管理

登录后使用 `GET /api/media?count=20` 查询本人的上传记录、referenceCount、usedCount 和 limit。用响应 nextCursor 作为下一页的 after 参数；末页 nextCursor 为 null。按 UUID 字符串排序，不是时间排序；新增图片后应从第一页刷新。

`GET /api/media?unusedOnly=true` 只返回没有作品引用的图片，便于挑选需要清理的上传内容。usedCount 仍是本人全部上传数量。列表仅为当前查询结果，最终删除会在事务内重新校验。

`DELETE /api/media/{mediaId}` 删除本人未引用的图片，成功返回 204 并释放一个上传名额；此操作没有回收站，客户端执行前应让用户确认。已删除或不属于本人的 ID 返回 404，不能用管理员身份代删；仍被任何草稿、待审、公开、驳回或下架作品引用时返回 409 MEDIA_IN_USE。先从所有相关作品移除引用再删除，单纯下架不会移除引用。数据库文件不保证立即缩小，已做备份和已下载副本也不会随之消失。
