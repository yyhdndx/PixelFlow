# [Java] 查看用户主页公开资料

状态：已实现并完成本地验收（2026-10-20）；开发分支 `feature/user-public-profile`，基于 issue 05 的提交 `4114a5d`。仓库内记录 issue，不另行发布线上 issue；未推送、合并或部署，目标环境 CI 待验证。

## 需求与边界

从作品作者、粉丝或关注卡片进入用户主页时，一次获得展示所需的公开资料、统计与本人关注状态。不占用收藏作品列表任务，不新增表或修改认证规则；不提供他人的私有资料、草稿和收藏，也不包含主页作品分页或 Android 页面实现。

## 契约

- `GET /api/users/{id}/profile`，需要有效 Bearer 登录身份。“公开”指字段的可见范围，不代表游客可以访问。
- id 是用户 ID（1–36 位字母、数字、下划线、连字符）；非法格式返回 400 `INVALID_ARGUMENT`。不存在及停用账号统一返回 404 `USER_NOT_FOUND`。
- 响应仅有 `user_id`、`nickname`、`bio`、`avatar`、`post_count`、`follower_count`、`following_count`、`is_following`，设置 `Cache-Control: no-store`。
- 作品数只计 PUBLISHED；粉丝/关注数仅计存在且启用的账号，与本人统计接口口径一致。
- `is_following` 由当前认证用户到目标用户的关注关系决定，不能通过 userId 查询参数伪造身份。允许通过真实 ID 查询本人；`me` 不作为本接口的别名。
- 简介和头像允许空字符串。不泄露用户名、角色、账号启用状态、密码或会话；屏蔽推荐不影响主页查看与统计。
- 单条参数化查询得到资料和统计，不逐项请求其他接口；无数据库迁移。

## 验收要求

字段白名单、空资料、中文资料、本人查询、匿名/无效会话拒绝、停用/不存在统一错误、非法 ID、作品状态过滤、无效关系过滤、双向关注、不同访问者关注状态、原关注接口更新后立即体现。H2 与独立 MySQL 共用用例，CI 使用独立 MySQL 8.4 测试库。

## Android 接入方式

从作品 `author.user_id` 或粉丝/关注卡片 `user_id` 取得目标 ID，带当前会话请求此接口。昵称、简介、头像直接展示；头像为空时使用客户端默认图。`is_following` 决定关注按钮状态，不能用粉丝数量推断。

```http
GET /api/users/<目标用户ID>/profile
Authorization: Bearer <accessToken>
```

成功响应示例（示例数据不代表种子账号）：

```json
{
  "user_id": "example-user",
  "nickname": "摄影同学",
  "bio": "记录生活",
  "avatar": "",
  "post_count": 3,
  "follower_count": 2,
  "following_count": 5,
  "is_following": false
}
```

关注或取消关注仍调用 `PUT /api/interactions/follows`，请求体为 `{"targetId":"目标用户ID","active":true}`（取消时 false）；成功后重新读取主页即可刷新统计和按钮。401 走现有令牌刷新流程，404 显示“用户不存在或不可见”，400 视为客户端传参问题。返回的是对象本身，不包裹 `user` 或 `status_code`；`/api/users/me/profile` 保留给既有 Python 画像代理，不是此接口的本人别名。

## 本地复测

在 `server` 目录执行：

```powershell
# 无需启动外部数据库
mvn '-Dtest=PublicUserProfileApiTest,OpenApiContractTest' test

# 先创建独立 MySQL 8.4 测试库 pf_public_profile_test，再配置测试专用账号
$env:MYSQL_TEST_PUBLIC_PROFILE_URL='jdbc:mysql://127.0.0.1:3306/pf_public_profile_test?useSSL=false&allowPublicKeyRetrieval=true'
$env:MYSQL_TEST_USER='<测试账号>'
$env:MYSQL_TEST_PASSWORD='<测试密码>'
mvn '-Dtest=PublicUserProfileApiTest,MySqlPublicUserProfileApiTest,OpenApiContractTest' test
```

MySQL 用例会清理指定库中的业务表，库名必须以 `_test` 结尾，绝不可使用共享业务库。环境变量只用于测试终端，不加入日常服务环境。CI 已配置独立 `pf_public_profile_test`，与其他测试套件分开。

## 验收结果（2026-10-20）

- Java 全量测试 147 项：92 通过、55 跳过、0 失败、0 错误，Maven 退出码 0。
- 本接口 H2 和 MySQL 共用 5 项测试，各 5 项通过；包括公开主页与本人统计的结果一致性断言。
- 上一项粉丝/关注列表 H2 和 MySQL 套件各 8 项通过；其余跳过的是未配置专用数据库的既有 MySQL 套件，不视为通过。
- OpenAPI 引用/契约检查、actionlint 工作流检查及 git diff 空白检查通过。
- 本地环境为 Java 22（项目编译目标 17）和 MySQL 9.0.1，本机 13306 独立测试实例；未访问共享业务数据库。目标 Java 17 / MySQL 8.4 已在 CI 配置，但本次尚未推送触发，不能将本地兼容性结果当作目标版本验收。
- 无新增数据库迁移，无需增加生产运行环境变量。主工作区原有未提交的部署文档及 IDE 文件保持不动。
