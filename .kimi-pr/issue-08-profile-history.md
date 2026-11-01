## 任务
issue IKP2FS（issue 08）：`GET /profiles/me/history` —— 提供本人历史兴趣画像摘要的游标分页。仅交付 Python 接口、测试与契约；Java 代理与 Android 页面不在范围。

## 改动
- `intelligence/src/pixelflow_intelligence/analytics/profile_history.py`（新增）：历史快照输出校验模块。白名单字段（version、rule_version、generated_at、window_days、timezone、cold_start、topics、media、event_counts、explanation）；校验文档为对象、user_id/version 与数据库行及认证用户一致、布尔不得充当整数、主题分数必须有限数值、计数非负；损坏快照抛出脱敏的 `InvalidProfileHistory`。
- `intelligence/src/pixelflow_intelligence/analytics/store.py`：新增小范围只读方法 `Store.profile_history(user_id, count, before_version)`。按认证用户 + `version < before_version` 数字降序在数据库内 `LIMIT count+1`，一条查询得到 items 与 has_more；不读全量历史、不加写锁、不合成 version=0 冷启动项、不触发同步/维护。
- `intelligence/src/pixelflow_intelligence/api/app.py`：注册 `GET /profiles/me/history`，带 `ProfileHistoryPage` 响应模型（OpenAPI 含响应模型与分页参数）。身份走现有 `JavaClient.me`；count 默认 20（1–50）、before_version 范围 1–9223372036854775807，显式空值/0/负数/小数/非数字/溢出/重复参数一律 422；`has_more` 为布尔，`next_cursor` 为本页最后版本的十进制字符串或 null；成功与错误均 `Cache-Control: no-store`；本地存储异常返回脱敏 503，不回显原始文档与异常。
- `intelligence/tests/test_profile_history.py`（新增，31 个用例）：空列表、1/2/10/100 数字倒序、首/中/末页及恰好 count 条、固定数据全量遍历不重不漏、大版本（2^63-1）合法、count/cursor 全部非法输入与重复参数、A/B 身份隔离与伪造 user_id 无效、缺/错/停用令牌 401、运维密钥不能代替用户身份、Java 不可用 503、GET 前后画像条数/内容/事件游标不变、翻页期间新增高版本不干扰、旧 rule_version 原样展示、损坏快照 15 种变体整页脱敏 503、范围外损坏不阻塞、字段白名单与中文主题/零计数/冷启动、错误响应 Cache-Control、OpenAPI 文档断言。
- `intelligence/tests/test_mysql.py`：新增 MySQL 8.4 只读分页用例（真实同步 + 重算产生的 version 1/2，游标跨页，GET 不改变快照数）。
- `contracts/http/python-analytics.md`、`intelligence/README.md`：补充接口契约与边界说明。

## 测试
- 纯 SQLite：`cd intelligence && uv run --extra test python -m pytest -q` → **167 passed, 5 skipped**（跳过为既有 MySQL 门禁用例）。
- MySQL 8.4 实测：本机 MySQL 8.4.2 独立空库 `pixelflow_analytics_test`（沿用 `MYSQL_TEST_ANALYTICS_DB` 门禁与既有清理夹具），`MYSQL_TEST_ANALYTICS_DB=mysql://... uv run --extra test python -m pytest -q` → **172 passed, 0 skipped**。测试库与账号为本地一次性配置，不含真实凭据。
- 现有同步、维护、最新画像、推荐预览测试全部保留并通过。

## 安全与边界
- 仅按认证 user_id 读取；query user_id、运维密钥、管理员角色均不能切换或替代身份。
- 分页期间新增更高版本不影响继续读取旧版本；允许版本空洞；响应描述生成当时的兴趣摘要，不按当前偏好重写。
- 响应与日志不回显原始文档、令牌、连接串；未修改 `mysql_schema.py`、旧迁移、同步游标、issue 07 的 Java 代码或 OpenAPI 文件。

## 不在范围
兴趣差分评分、画像删除、数据保留策略、Java 历史画像代理、Android 页面、MCP/Agent、数据库迁移与部署。
