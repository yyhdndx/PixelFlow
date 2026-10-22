# Java 用户主页公开作品分页验收记录

合并整合后的复测与认证并发修复见 [作者作品与收藏分支整合验收](../testing/作者作品与收藏分支整合验收.md)，该记录包含全部 MySQL 套件的实际运行结果；下文保留原功能分支验收过程。

验收日期：2026-10-22。分支：`feature/issue-08-profile-history`。本记录仅覆盖用户主页公开作品 Issue，不表示 Android 设备验收完成。

## 需求与边界

新增 `GET /api/users/{id}/posts`，必须登录；用户 ID 为 `[A-Za-z0-9_-]{1,36}`，me 按普通 ID 查询。每页检查目标用户存在且启用，不存在和停用统一 404 `USER_NOT_FOUND`。仅返回目标作者 PUBLISHED 作品，包含图片/视频；作者和管理员同样不能看到其他状态，不套用推荐偏好、屏蔽或不感兴趣。

count 省略默认 20，范围 1–50；cursor 首页省略，否则为解析值 1–Long.MAX_VALUE 的十进制数字字符串，允许前导零。空值、裸参数、非整数或溢出统一 400 `INVALID_ARGUMENT`。SQL 使用绑定参数，过滤后按数字作品 ID 降序取 count+1，首页无游标上界；仅存在额外记录才返回 has_more=1，并以本页末项 ID 字符串作为 next_cursor，否则为 null。无跨页快照，数据变化后从首页刷新。

响应仅 `status_code/post_list/has_more/next_cursor`，Cache-Control 为 no-store。卡片复用 PostService.view，按认证访问者查询 is_liked，使用规范作品 ID 字符串匹配点赞，不增加收藏字段或作者简介。没有修改 PostService、认证、迁移、Python、Android、媒体权限和状态流转。

## 复测与环境

使用 Java 17.0.12、Maven 3.10.0、Spring Boot 3.5.16、H2 2.3.232、MySQL **8.4.13**；测试关闭种子及管理员初始化。应用不读取 `.env`。实际连接本机 `127.0.0.1:3306` 执行 `SELECT VERSION()` 确认版本，用已授权的本地账号新建唯一专库，未修改已有库或账号权限：

```sql
CREATE DATABASE pf_user_posts_20261022_8f7d2b91_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
```

在仓库根目录使用 Git Bash 执行以下命令；`MYSQL_TEST_USER`、`MYSQL_TEST_PASSWORD` 通过本地私有环境注入，不写入文档。两次 Maven 运行均只配置本 Issue 专库，其余 MySQL 测试 URL 均未配置：

```bash
export JAVA_HOME='C:/Program Files/Java/jdk-17'
export PATH="$JAVA_HOME/bin:$PATH"
# 先清除已有 MYSQL_TEST_* 变量，再通过本地私有环境注入账号凭据
export MYSQL_TEST_USER_POSTS_URL='jdbc:mysql://127.0.0.1:3306/pf_user_posts_20261022_8f7d2b91_test?useSSL=false&allowPublicKeyRetrieval=true'
mvn -B -ntp -f server/pom.xml -Dtest=UserPostsApiTest,UserPostsHttpTest,OpenApiContractTest,MySqlUserPostsApiTest test
mvn -B -ntp -f server/pom.xml verify
```

数据库名必须以 `_test` 结尾，套件断言实际版本以 `8.4.` 开头。首次专项运行在新专库自动成功应用既有 V1–V7，标准 verify 再次校验。专库保留用于复核；后续新建环境应选择新的唯一 `_test` 库名。CI verify 沿用 mysql:8.4，专库为 `pf_user_posts_test`，无部署作业改动。

真实 HTTP 测试会启动随机本机端口、使用独立 H2 内存测试库、通过 HTTP 注册并登录本地用户，以确定性数据库夹具准备公开作品，再通过 Java HttpClient 调用接口和撤回接口；不会请求外部素材。可单独执行：

```bash
mvn -B -ntp -f server/pom.xml -Dtest=UserPostsHttpTest test
```

仅复测 MySQL 时，在上述专库及凭据环境下执行 `mvn -B -ntp -f server/pom.xml -Dtest=MySqlUserPostsApiTest test`。

## 验证结果与剩余缺口

| 验证 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| 新增 H2 UserPostsApiTest | 8 | 0 | 0 |
| 真实 HTTP UserPostsHttpTest | 1 | 0 | 0 |
| OpenApiContractTest | 1 | 0 | 0 |
| MySqlUserPostsApiTest（MySQL 8.4.13） | 8 | 0 | 0 |
| 专项合计 | 18 | 0 | 0 |
| 标准 mvn verify（总计 164） | 96 | 0 | 68 |

专项于 2026-10-22 16:36:16 +08:00 完成，标准 verify 于 16:38:19 +08:00 完成，均为 BUILD SUCCESS。标准 verify 再次运行本 Issue MySQL 8 项、H2 8 项、真实 HTTP 1 项及 OpenAPI 1 项，全部零失败、零跳过，并生成可执行 `server/target/pixelflow-server-0.1.0.jar`；既有 Feed、作品、关注、资料及身份测试保留。68 项跳过均来自本次未配置的其他 MySQL 套件：Business 32、Migration 2、Preference 9、PublicUserProfile 5、UserConnections 8、UserStats 12。不要将 target 下历史遗留的其他 Issue 报告算入本次结果，以本次 Maven 汇总为准。

本 Issue 后端验收已完成，原缺少 MySQL 8.4 的阻塞已解除。真实 MySQL 8.4.13 专库与 H2 均通过，本次无须修改业务实现。其他 MySQL 套件全配置回归及 Android 设备验收不在本次完成范围。

构建存在非阻塞提示：Flyway 提示当前版本尚未验证 MySQL 8.4 支持（既有 V1–V7 实际应用及再次校验均成功）；Maven 的可选 flyway-community-db-support 元数据请求返回 401，未影响测试、打包和 BUILD SUCCESS。未调整依赖或迁移。

覆盖作者隔离、访问者点赞、伪造身份参数、推荐偏好无影响、401/400/404、本人及管理员私有状态过滤、me 字面 ID、空/恰好/额外记录、首中末页遍历、9/10/100 数字排序、Long.MAX_VALUE 首页及游标、count 默认/边界/溢出、cursor 边界/溢出、过滤先于 LIMIT、撤回立即消失、后续页目标停用与恢复、中文卡片、媒体顺序和字段类型、详情/Feed 对照与 no-store。真实 HTTP 另验证 `?count=`、`?count`、`?cursor=`、`?cursor` 全部返回 400 INVALID_ARGUMENT。

## 实际 HTTP 首末页与撤回响应

以下响应已于 2026-10-22 本轮专项和标准 verify 的 UserPostsHttpTest 中重新实测，标准 verify 使用本机随机端口 `56246`。随机 UUID 已统一替换为 `http-author`，JSON 对象键顺序仅作排版整理；访问令牌仅用占位符，不含真实凭据。首末页、撤回及撤回后刷新均实际返回 HTTP 200，首页带 `Cache-Control: no-store`；裸参数与显式空参数的四个请求均为 HTTP 400 `INVALID_ARGUMENT`。测试服务在运行结束自动关闭。可在隔离测试服务使用如下请求形状：

```bash
curl -H 'Authorization: Bearer <access-token>' 'http://127.0.0.1:<port>/api/users/<user-id>/posts?count=2'
curl -H 'Authorization: Bearer <access-token>' 'http://127.0.0.1:<port>/api/users/<user-id>/posts?count=2&cursor=10'
curl -X POST -H 'Authorization: Bearer <access-token>' 'http://127.0.0.1:<port>/api/posts/100/withdraw'
curl -H 'Authorization: Bearer <access-token>' 'http://127.0.0.1:<port>/api/users/<user-id>/posts?count=2'
```

首请求 HTTP 200，Cache-Control: no-store：

```json
{"status_code":0,"post_list":[{"post_id":"100","title":"作品100","content":"中文正文","create_time":1,"status":"PUBLISHED","media_ids":[],"author":{"user_id":"http-author","avatar":"","nickname":"本地作者"},"clips":[],"hashtag":[],"music":null,"like_count":0,"is_liked":false},{"post_id":"10","title":"作品10","content":"中文正文","create_time":1,"status":"PUBLISHED","media_ids":[],"author":{"user_id":"http-author","avatar":"","nickname":"本地作者"},"clips":[],"hashtag":[],"music":null,"like_count":0,"is_liked":false}],"has_more":1,"next_cursor":"10"}
```

末页 HTTP 200：

```json
{"status_code":0,"post_list":[{"post_id":"9","title":"作品9","content":"中文正文","create_time":1,"status":"PUBLISHED","media_ids":[],"author":{"user_id":"http-author","avatar":"","nickname":"本地作者"},"clips":[],"hashtag":[],"music":null,"like_count":0,"is_liked":false}],"has_more":0,"next_cursor":null}
```

撤回 HTTP 200：

```json
{"postId":"100","status":"WITHDRAWN"}
```

撤回后刷新首页 HTTP 200：

```json
{"status_code":0,"post_list":[{"post_id":"10","title":"作品10","content":"中文正文","create_time":1,"status":"PUBLISHED","media_ids":[],"author":{"user_id":"http-author","avatar":"","nickname":"本地作者"},"clips":[],"hashtag":[],"music":null,"like_count":0,"is_liked":false},{"post_id":"9","title":"作品9","content":"中文正文","create_time":1,"status":"PUBLISHED","media_ids":[],"author":{"user_id":"http-author","avatar":"","nickname":"本地作者"},"clips":[],"hashtag":[],"music":null,"like_count":0,"is_liked":false}],"has_more":0,"next_cursor":null}
```
