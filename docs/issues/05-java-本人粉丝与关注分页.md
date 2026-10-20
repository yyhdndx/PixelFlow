# [Java] 提供本人粉丝与关注用户卡片列表

状态：按团队约定以仓库 Markdown 记录 issue，不另行发布线上 issue。实现与验收记录见文末。开发分支：`feature/user-connections`。

## 用户价值

Android 个人主页已有粉丝/关注数字及点击入口，但后端只有关注 ID 列表，缺少可直接展示的用户卡片。本任务补齐列表查询，避免逐个请求用户资料，与正在进行的收藏作品列表任务独立。

## 接口约定

- `GET /api/users/me/followers`：关注当前用户的启用账号。
- `GET /api/users/me/following`：当前用户关注的启用账号。
- 使用既有 Bearer 认证，只能查询当前登录用户，不通过 userId 切换身份。
- `count` 默认 20，范围 1～50；`cursor` 可省略，提供时为上一页返回的用户 ID（1～36 位字母、数字、下划线或连字符）。显式空值、非法格式和越界返回 400 / INVALID_ARGUMENT。
- 以用户 ID 字符串升序做 keyset 分页，不承诺关注时间排序或跨页快照；SQL 完成关系归属、启用状态和 cursor 筛选后读取 count+1 条。
- 响应：`{status_code:0,user_list:[],has_more:0,next_cursor:null}`；has_more 为数字 0/1，有下一页时 next_cursor 为本页最后一个用户 ID，否则 null。设置 `Cache-Control: no-store`。
- 用户卡片仅含 `user_id`、`nickname`、`bio`、`avatar`、`is_following`。最后一个字段表示当前用户是否关注该卡片用户，粉丝列表可据此显示“回关”；关注列表恒为 true。
- 排除停用和不存在的账号，恢复启用后重新出现。点赞、收藏、屏蔽作者和推荐偏好不改变关注关系口径。取消关注后重新查询反映最新状态，不删除不可见关系。
- 不返回用户名、密码哈希、角色、账号状态、会话或其他私有字段。

## 实现边界

独立控制器和查询服务；一条参数化 SQL 生成每页及回关状态，不逐项查库。不改变旧 GET/PUT `/api/interactions/follows`、资料和统计接口，不新增表/迁移，不做 Android 页面、不部署共享服务器。

## 验收

- 空列表、默认分页、count 1/50、正好一页、多页遍历无重复/遗漏。
- 两个账号隔离、伪造 userId 不越权、匿名/失效令牌/停用账号被拒绝。
- 作者资料中文、头像、简介完整；字段白名单；双向/单向关注标识准确。
- 不可见账号在分页前过滤；不存在目标不造成异常或提前结束。
- 通过原关注接口取消/重新关注后列表和回关标识更新；停用/启用关系保留。
- 参数非法返回既有错误格式；H2 与独立 MySQL 测试共用用例；CI 配置独立 MySQL 8.4 测试库。
- 不访问共享业务数据库，不修改此前其他任务的本地未提交文件。

## 实现与验收记录

已实现独立 `UserConnectionsController` / `UserConnectionsQueryService`，补齐 OpenAPI、server README、开发指南和 CI 独立测试库。无需新增运行环境变量或数据库迁移；Android 页面接入不在本任务范围内。

本地 Java 回归：137 项中 82 通过、55 跳过、0 失败。新增 H2 / MySQL 列表套件各 8 项全部通过；跳过的是未配置专用数据库的既有 MySQL 套件。此次本地使用 Java 22（编译目标 17）及仅监听本机 13306 的 MySQL 9.0.1 独立测试实例，未访问云端业务库。本地结果不能替代目标 Java 17 / MySQL 8.4 的 CI 验证，已为 CI 配置 `pf_connections_test`。工作流通过 actionlint 检查。

复测（在 server 目录）：

```powershell
# 快速 H2 验证，不需要启动 MySQL
mvn '-Dtest=UserConnectionsApiTest,OpenApiContractTest' test

# 先在本机 MySQL 8.4 创建独立 pf_connections_test 库，使用测试专用凭据
$env:MYSQL_TEST_CONNECTIONS_URL='jdbc:mysql://127.0.0.1:3306/pf_connections_test?useSSL=false&allowPublicKeyRetrieval=true'
$env:MYSQL_TEST_USER='<测试账号>'
$env:MYSQL_TEST_PASSWORD='<测试密码>'
mvn '-Dtest=UserConnectionsApiTest,MySqlUserConnectionsApiTest,OpenApiContractTest' test
```

测试会清理指定测试库中的业务数据；库名必须以 `_test` 结尾，绝不可指向共享库。合并前应确认 GitHub CI 的 MySQL 套件未跳过。
