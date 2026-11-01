## 任务
issue IKORUJ：提供 Android「我的」页面作品/粉丝/关注三个统计值的一次性聚合读取接口。

## 改动
- `server/.../users/UserStatsController.java`（新增）：`GET /api/users/me/stats`，用户身份只来自 Bearer 认证上下文，不接受 userId 参数；无包装 200，`Cache-Control: no-store`。
- 单次参数化读取：一条含三个 COUNT 子查询的 SELECT（post_count 仅计 PUBLISHED；follower/following 要求对方 users 行存在且 enabled）；DB 异常直接传播现有 500 处理，不伪造 0。
- `UserStatsApiTest`（H2 MockMvc，6 个用例）与 `MySqlUserStatsApiTest`（沿用项目 `MYSQL_TEST_*` 环境变量门禁惯例）；未修改他人测试文件与 `UserController`。
- `contracts/http/java-api.yaml`：仅新增 `/api/users/me/stats` 路径与 `UserStats` schema，未改现有定义。

## 测试
- `mvn test`（server/）：93 tests，0 failures / 0 errors；新增用例 6/6 通过，覆盖：新账号 0/0/0、匿名与无效令牌 401、仅 PUBLISHED 计入、关注/取消关注双方计数联动、60 粉丝/55 关注返回完整总数、停用账号不计入、no-store 头。
- MySQL 8.4 集成测试已注册但本机无法启动服务（Windows 服务启动需管理员权限，未设置 `MYSQL_TEST_*` 环境变量），标记跳过；将在具备 MySQL 测试库的环境运行，未以“跳过”代替验收结论。

## 示例
成功响应：
```
GET /api/users/me/stats
HTTP/1.1 200
Cache-Control: no-store

{"user_id":"u-123","post_count":2,"follower_count":60,"following_count":55}
```
鉴权失败：匿名或无效 Bearer → `401`（沿用现有安全过滤器行为）。

## 说明
- 无新增配置、无迁移版本占用、无新增依赖。
- 既有关注写入接口行为不变，测试中以真实调用验证。
