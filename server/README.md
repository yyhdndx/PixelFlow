# Java 核心业务服务

首次开发先按 [后端开发上手指南](../docs/后端开发上手指南.md)创建本地 MySQL 库，复制 [.env.example](.env.example) 并显式加载配置。本文用于模块实现和参数查询；云端状态见 [服务器部署记录](../deploy/服务器部署记录.md)。

独立 Maven 工程：Spring Boot 3.5.16、Java 17、Spring Security、Spring JDBC、Flyway。默认使用 MySQL，H2 仅用于离线测试和旧数据迁移。建库、配置和旧数据导入见 [MySQL 部署与迁移](../deploy/MySQL部署与迁移.md)。

源码根目录：`src/main/java/com/pixelflow/server/`。

Redis限流默认关闭，显式设置 `PIXELFLOW_RATE_LIMIT_ENABLED=true` 后保护登录、注册及认证上传；规则和Redis变量见 [.env.example](.env.example)，部署和可信单跳IP配置见 [Redis限流部署](../deploy/Redis限流部署.md)。429使用 `RATE_LIMITED`，包含 `Retry-After`、`Cache-Control: no-store` 和真实请求标识。Redis故障有限超时后放行，保护暂时减弱。

真实Redis专项在仓库根目录执行 `mvn -B -ntp -f server/pom.xml -Predis-integration verify`，使用 `REDIS_TEST_HOST`（默认127.0.0.1）、`REDIS_TEST_PORT`（6379）、`REDIS_TEST_USERNAME` / `REDIS_TEST_PASSWORD`、`REDIS_TEST_DATABASE`（0）。须指向专属本地/受控测试实例；专项唯一前缀且只清理自己的键，Redis不可用直接失败，不静默跳过。普通 `mvn test` 不包含该专项，不能作为Redis验收。结果及Android调用示例见 [Redis限流验收](../docs/testing/Redis限流验收.md)。

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

Flyway 自动选择 `src/main/resources/db/mysql/` 或 `db/h2/`。H2 历史脚本内容及校验和保留；双库维护 V1–V7 和 V9（作品创建幂等记录）。V8 已由头像分支占用，须先合入/部署真实 V8 再部署 V9；本分支不制造 V8 占位，也不启用 out-of-order 或 repair。V7 为旧账号补充默认空简介。新增结构修改必须追加版本，不能改已执行的脚本。H2 的 PostgreSQL 模式只是测试兼容选项，不代表部署 PostgreSQL。

`POST /api/posts` 可选 `Idempotency-Key`：单个标准 UUID，大小写等价；无键仍每次201，新键201，同用户同键同 Draft 重试200并返回当前作品，同键不同请求409 `IDEMPOTENCY_CONFLICT`。空/重复/非法头400，认证失败401，原作品不存在404且不补建。记录不自动过期；文本不trim，媒体顺序保留，null/省略列表按空列表处理。客户端须持久保存一次逻辑创作的键及原 Draft，取得 ID 后改用编辑/提交接口。调用示例、Kotlin示意及验收见 [创建幂等验收](../docs/issues/java-作品创建幂等.md)。

专项执行：在 server 目录配置独立的 `MYSQL_TEST_POST_IDEMPOTENCY_URL` 和 `MYSQL_TEST_POST_IDEMPOTENCY_RESTART_URL`（均为本机、库名以 `_test` 结尾）及 `MYSQL_TEST_USER` / `MYSQL_TEST_PASSWORD` 后运行 `mvn -B -ntp -Dtest=PostIdempotencyApiTest,MySqlPostIdempotencyApiTest,PostIdempotencyRestartTest,MySqlPostIdempotencyRestartTest,OpenApiContractTest test`。重启迁移套件会清理其专属测试库；两套件不可共库。未配置 MySQL 时跳过不算验收通过。

## 本人资料

登录后 `GET /api/users/me` 返回原有身份字段及非 null 的 `bio`、`avatar`，响应禁止缓存。`PUT /api/users/me` 仅完整替换昵称和简介；两字段均必填，昵称 trim 后为 1–40 个 UTF-16 单元，简介原样保存、最多 60 个 UTF-16 单元。未知字段或非法输入返回 400 `INVALID_ARGUMENT`，不修改头像和角色。

```bash
curl -H "Authorization: Bearer <access-token>" http://127.0.0.1:8080/api/users/me
curl -X PUT -H "Authorization: Bearer <access-token>" -H "Content-Type: application/json" \
  -d '{"nickname":"小明","bio":"摄影与旅行"}' http://127.0.0.1:8080/api/users/me
```

本地验收可在独立测试库启动服务，注册并登录后按上述顺序 GET、PUT、重新登录再 GET；不要在日常业务库试验迁移。

## Android 联调

### 本人收藏作品

`GET /api/users/me/favorites?count=20` 返回可直接展示的图文/视频卡片，下一页传 `cursor=<next_cursor>`。响应沿用 Feed 的 `status_code`、`post_list`、数字 `has_more` 和字符串或 null 的 `next_cursor`；卡片含请求用户的 `is_liked` 及恒为 true 的 `is_favorited`。只返回启用作者的 PUBLISHED 作品，作者本人和管理员也不能通过收藏页看到私有作品。

按作品数字 ID 降序，不是收藏时间排序；过滤不可见作品后再取 count+1 条，取消收藏、撤回或停用作者会影响后续查询。既有 GET/PUT `/api/interactions/favorites` 保持兼容。参数 count 默认20、范围1–50；cursor 为正整数 long；显式空值、重复分页参数、非十进制数字或越界返回400 `INVALID_ARGUMENT`。成功和业务错误响应禁止缓存，不接受查询参数切换身份。

### 本人历史画像

`GET /api/users/me/profile/history?count=20` 使用当前用户 Bearer token，下一页传 `before_version=<next_cursor>`。Java 仅通过配置的 `PIXELFLOW_INTELLIGENCE_BASE_URL` 访问内部 Python；Android 不直接访问 Python。返回 `user_id`、`items`、布尔 `has_more` 及字符串或 null 的 `next_cursor`，与收藏列表的 has_more 类型不同。

items 是版本降序的已保存摘要：version、rule_version、generated_at、window_days、timezone、cold_start、topics、media、event_counts、explanation。历史摘要不按当前抑制主题重写，不触发同步或重算，无历史时返回空 items。Java 校验本人归属、字段类型、版本顺序及游标一致性，剔除未约定字段。参数错误返回400；上游401保留为401；无效JSON/摘要返回502 `INVALID_ANALYTICS_RESPONSE`；未配置、连接失败、三秒请求超时或其他上游非成功状态返回503 `ANALYTICS_UNAVAILABLE`。全部响应禁止缓存，不向客户端透出上游错误正文，不跟随重定向。

本轮接口交付及测试见 [后端接口补全验收](../docs/testing/后端接口补全验收.md)。新增接口无需数据库迁移；作者作品分页仍由任务07交付，头像接口仍在独立分支。

### 本人粉丝与关注列表

登录后调用 `GET /api/users/me/followers?count=20` 或 `GET /api/users/me/following?count=20`。响应包含 `status_code`、`user_list`、数值型 `has_more`（0/1）和 `next_cursor`。用户卡片仅包含 `user_id`、`nickname`、`bio`、`avatar`、`is_following`；粉丝列表的 `is_following` 表示是否回关。关注/取消仍调用原有互动接口。

下一页传 `cursor=<next_cursor>`，末页游标为 null；第一页省略 cursor，不能传空字符串。count 范围 1–50。按用户 ID 字符串升序，不是关注时间排序；账号停用和关系变化可能改变结果，刷新时从第一页开始。不存在/停用账号在分页前过滤，不受推荐屏蔽偏好影响。接口只认登录身份，不接受查询其他用户；不新增表和迁移。

验收与独立 MySQL 测试方式见 [issue 05](../docs/issues/05-java-本人粉丝与关注分页.md)。

### 本人收藏作品分页

登录后调用 `GET /api/users/me/favorites`，返回 `status_code`、`post_list`、数值型 `has_more`（0/1）和 `next_cursor`，响应 `Cache-Control: no-store`。卡片复用 Feed 字段，包含图文与视频、当前用户 `is_liked` 以及恒为 true 的 `is_favorited`。

```bash
curl -H "Authorization: Bearer <access-token>" 'http://127.0.0.1:8080/api/users/me/favorites?count=20'
curl -H "Authorization: Bearer <access-token>" 'http://127.0.0.1:8080/api/users/me/favorites?count=20&cursor=<next_cursor>'
# 400 INVALID_ARGUMENT：显式空游标
curl -H "Authorization: Bearer <access-token>" 'http://127.0.0.1:8080/api/users/me/favorites?cursor='
mvn -B -ntp -f server/pom.xml -Dtest=FavoritePostsApiTest,FavoritePostsHttpTest,OpenApiContractTest,BusinessApiTest test
# 配置独立 MYSQL_TEST_FAVORITES_URL、MYSQL_TEST_USER、MYSQL_TEST_PASSWORD 后验证 MySQL 8.4
mvn -B -ntp -f server/pom.xml -Dtest=MySqlFavoritePostsApiTest test
```

上述 Maven 命令在仓库根目录执行。count 省略默认 20、范围 1–50；count 与 cursor 均允许前导零；cursor 为正十进制 long（最大 9223372036854775807），第一页省略，空值、重复分页参数、符号、空白、小数、溢出均返回 400 `INVALID_ARGUMENT`。仅有下一页时游标为本页末项作品 ID 字符串，否则为 null。按作品数字 ID 倒序，重新收藏不改变位置；无跨请求快照，收藏或状态变化后从第一页刷新。分页前过滤非公开、作者停用或不存在的作品，作者本人和管理员也不豁免；隐藏不会自动清除收藏，恢复后重新出现。取消/重新收藏仍用 `PUT /api/interactions/favorites`，原 GET ID 列表保持不变。

### 查看作者或其他用户主页

`GET /api/users/{id}/profile` 需要 Bearer 登录身份，返回 `user_id`、`nickname`、`bio`、`avatar`、`post_count`、`follower_count`、`following_count` 和 `is_following`。最后一个字段表示访问者是否关注此用户，不是反向关系。仅返回启用账号；不存在和停用均为 404。只提供公开资料，不含用户名、角色、草稿或收藏；作品列表不在本接口内。资料统计口径与本人统计一致，响应禁止缓存；本人资料编辑仍使用 `/api/users/me`。

验收与复测见 [issue 06](../docs/issues/06-java-用户主页公开资料.md)。

### 用户主页公开作品分页

`GET /api/users/{id}/posts?count=20` 需要 Bearer 登录身份，返回 `status_code`、`post_list`、数值型 `has_more`（0/1）和字符串或 null 的 `next_cursor`，响应 `Cache-Control: no-store`。作品卡片复用详情字段，`is_liked` 表示当前访问者是否点赞，不包含收藏字段或作者简介。

仅返回目标启用用户的 PUBLISHED 作品，包含图片和视频；作者本人、管理员也不能在此看到草稿等私有状态，不应用推荐屏蔽、不感兴趣和推荐偏好。真实 ID 可查本人，`me` 按普通 ID 查询。每页重新检查作者状态，不存在或停用返回 404 `USER_NOT_FOUND`。

按数字作品 ID 降序分页，首页省略 cursor，下一页传 `cursor=<next_cursor>`。count 省略为 20，范围 1–50；cursor 为 1–Long.MAX_VALUE 的十进制数字字符串，允许前导零。空值、裸参数、非整数或越界均为 400 `INVALID_ARGUMENT`。末页/空页 `has_more=0`、`next_cursor=null`。无跨页快照，新增、撤回或账号状态变化后从首页刷新。

```bash
curl -H "Authorization: Bearer <access-token>" 'http://127.0.0.1:8080/api/users/<user-id>/posts?count=2'
curl -H "Authorization: Bearer <access-token>" 'http://127.0.0.1:8080/api/users/<user-id>/posts?count=2&cursor=<next_cursor>'
mvn -B -ntp -f server/pom.xml -Dtest=UserPostsApiTest,UserPostsHttpTest,OpenApiContractTest test
```

独立 MySQL 8.4 复测使用 `MYSQL_TEST_USER_POSTS_URL`（数据库名必须以 `_test` 结尾）及已有 `MYSQL_TEST_USER`、`MYSQL_TEST_PASSWORD`，执行 `-Dtest=MySqlUserPostsApiTest test`。未配置时跳过，不算 MySQL 验收通过；套件会检查实际版本为 8.4。真实 HTTP 示例和验收状态见 [用户主页作品分页](../docs/issues/java-用户主页作品分页.md)。

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
