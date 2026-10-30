# Python 数据分析与智能服务

首次开发先阅读 [后端开发上手指南](../docs/后端开发上手指南.md)，使用 Python 3.11，复制 [.env.example](.env.example) 并显式加载环境变量。本页保留模块和接口细节，不要直接跳过数据库与令牌配置执行启动命令。

状态：已实现 FastAPI 服务、Java 事件增量同步、MySQL 事件与画像快照、用户本人画像查询、本人历史画像游标分页、五个业务 Skill 和注册表（推荐侧 `profile_insight`/`candidate_recall`/`feed_rank`，创作侧 `content_safety`/`creator_assist`），以及读取真实 Java Feed 的本人推荐预览。SQLite 保留用于离线测试和旧数据迁移。Skill 使用确定性规则，不调用模型、不需要模型密钥；Agent 与面向客户端的正式推荐入口尚未实现。

部署时设置 `ANALYTICS_DB=mysql://pixelflow_analytics:<URL编码后的密码>@127.0.0.1:3306/pixelflow_analytics`。省略时默认连接本机 MySQL，同名账号、不含密码，因此必须先配置账号。不要提交带密码的 URL。MySQL 初始化版本由 `analytics/mysql_schema.py` 管理；同步与重算共用 checkpoint 行锁，状态查询使用一致快照。完整步骤见 [MySQL 部署与迁移](../deploy/MySQL部署与迁移.md)。

源码采用 `src/pixelflow_intelligence/` 布局。

|目录|职责|
|---|---|
|api|内部 HTTP 路由、输入输出校验|
|analytics|行为清洗聚合、用户画像和内容特征|
|recommendation|召回、排序、去重及热门策略|
|agents|目标解析、计划、Skill 选择、执行状态和结果整合|
|skills|项目业务 Skill、注册表、输入输出约束|
|mcp|MCP Server、工具定义及 Java/Python 接口适配|
|evaluation|策略与 Agent 的固定测试集评测|
|clients|Java 服务和模型提供方客户端|
|common|配置、日志、错误和 traceId|

已实现 profile_insight（画像解读与降级）、candidate_recall（候选池内兴趣/热门召回）、feed_rank（排序与多样性过滤）。注册表提供输入输出校验、身份与 scope 检查、协作式超时及 trace_id。content_safety、creator_assist 仍是预留目录。

推荐侧三个 Skill 只消费可信调用方提供的画像和候选快照，不自行查询数据库；推荐交付前仍需 Java 复核状态、权限和负反馈。创作侧 `content_safety`/`creator_assist` 同样是确定性规则、不访问网络与数据库，只产出建议、不改作品状态。完整边界、字段 Schema 导出方式及评分规则见 [Skill 契约](../contracts/skills/README.md)。在本目录运行 `.\.venv\Scripts\python.exe scripts/demo_skills.py` 可验证美食、数码与冷启动三组离线调用，`scripts/demo_content_skills.py` 演示创作侧两个 Skill；示例是固定流水线，不是 Agent。

单元测试位于 `tests/`，公共评测样本位于仓库根目录 `tests/fixtures/`。业务 Skill 的输入、输出和权限约束记录在 `contracts/skills/`。实现 MCP 时复用身份和权限校验，不允许模型直接控制数据库写操作。

联调入口为 GET /recommendations/me/preview，携带 Java 用户 access token。它读取 Java 权威偏好、本人画像及最近50条公开作品，应用作品排除、作者屏蔽、主题权重抑制和个性化开关，再组合三个 Skill 并复核公开 Feed 与偏好版本。Python GET /recommendations/me/preferences 只读代理 Java，旧的三个偏好 PUT 端点返回410。屏蔽仅影响本人推荐，不封禁作者、不改变作品访问权限；完整响应和限制见 [HTTP 契约](../contracts/http/python-analytics.md)。

旧 SQLite 偏好不能自动忽略。升级时先备份，再使用 `python -m pixelflow_intelligence.migrate_preferences --database <旧库备份路径>` 按当前认证账号检查；只有加 --apply 才导入，Java 已有不同偏好时拒绝覆盖。完整步骤见 [旧偏好迁移与修复验证](../docs/architecture/旧偏好迁移与修复验证.md)。

## 安装与启动

Python 3.10+，从 intelligence 目录执行。requirements-lock.txt 固定本轮已测试的运行及测试依赖；虚拟环境和 runtime-data 不提交。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
.\.venv\Scripts\python.exe -m pip install -e . --no-deps
$env:JAVA_BASE_URL='http://127.0.0.1:8080'
.\.venv\Scripts\python.exe -m uvicorn pixelflow_intelligence.api.app:create_app --factory --host 127.0.0.1 --port 8001
```

启动前还需从私有环境配置注入两枚不同的随机服务密钥（各至少32个 ASCII 字符，不提交、不截图、不写进 Android）：

|变量|配置在哪一侧|权限|
|---|---|---|
|PIXELFLOW_ANALYTICS_EXPORT_TOKEN|Java 与 Python 使用同一值|Python 读取 Java 的 /api/internal/analytics/events，只有 events:read 权限|
|PIXELFLOW_INTELLIGENCE_TOKEN|Python 与受控调度/运维调用方|事件同步、画像重算及 GET /internal/status 汇总状态|
|ANALYTICS_DB|Python，部署必填|mysql://账号:URL编码密码@127.0.0.1:3306/pixelflow_analytics；显式文件路径仅用于 SQLite 兼容测试|

Java 未配置导出密钥时该接口全部拒绝；Python 未配置同步密钥时同步返回503。管理员 JWT 不代替服务身份。不要使用示例字符串作为部署密钥，也不要复用两个服务密钥。

GET /health 检查进程。运维调用方带第二枚密钥 POST /internal/sync，每次同步一页，has_more=true 时继续。仓库已有 `scheduled_maintenance` 包装器（续跑游标、状态目录互斥、退出码），但它只运行一轮，不会自行创建定时器；即使没有更多历史数据，后续也要再次调用以拉取新事件。

GET /internal/status 使用同一运维密钥，查询事件数、画像人数/版本数、冷启动和过期画像数量，以及最近同步页/重算页成功时间。成功记录与业务事务一起提交，失败不覆盖；旧库启动时自动补建记录表，但不伪造历史执行时间。该接口只读本地库，不暴露用户明细，也不检测 Java 是否可用或已追平，upstream_backlog 始终为 unknown。适合低频人工排查，不替代 /health 存活探针。

画像维护使用同一运维密钥 POST /internal/profiles/refresh，请求体 `{}` 默认扫描50名用户并重算超过24小时的画像。has_more=true 时传回 next_cursor 作为下一页 after，完整扫描后的下一轮重新从空游标开始。规则变化或异常未来时间也会触发重算；整页事务与同步互斥，失败可重试。重算不改变同步游标，不清理数据或修改推荐偏好。当前需要调用方周期触发，并非自动后台任务。

GET /profiles/me 使用 Java 登录得到的用户 access token，Python 每次向 Java 验证当前用户；不允许指定别人的 userId。Android 使用 Java GET /api/users/me/profile 代理查询，在“我的 → 账号与登录 → 查看我的兴趣画像”展示版本和主题分。Java 启动前设置 PIXELFLOW_INTELLIGENCE_BASE_URL=http://127.0.0.1:8001（这是 Java 访问 Python 的地址，不是手机地址）。字段、错误码及样例见 [Python API 契约](../contracts/http/python-analytics.md)。

GET /profiles/me/history 使用同一用户 access token，返回本人已保存的历史画像快照：version 数字降序游标分页（count 默认20、范围1–50；before_version 为上一页 next_cursor）。接口只读持久化快照，不合成冷启动项、不按当前偏好重写历史，也不改变事件游标或推荐偏好；Java 侧历史画像代理与 Android 历史页面另行任务接入。字段白名单、错误语义及样例见 [Python API 契约](../contracts/http/python-analytics.md)。

## 数据与画像规则

- Java V5 增加停留时长、场景、事件来源、稳定导出序列与审计。客户端只能生成 CLIENT 事件；点赞/取消点赞的 SERVER 事件与互动同事务写入，重复设置不重复发事件。
- MySQL 保存来源 ID、事件、同步游标及按版本保存的画像。每页导入、画像更新、游标推进同事务完成；坏数据/超时回滚，源数据库更换时拒绝复用游标。不要手工回退游标；重建时使用独立分析库并保留旧库备份。
- 规则使用生成时最近30天事件：点击每作品每天最多1分；停留每作品每天最多120秒，30秒=1分；当前有效的服务端点赞3分，取消移除。曝光只作次数统计，客户端自报 LIKE 不增加点赞权重。
- 基于标题关键词分类美食、宠物、旅行、阅读、数码、运动、园艺，未匹配归为其他；并聚合作者、图片/视频偏好及 UTC 活跃时段。规则可解释，但没有训练或验证推荐准确率。
- 新用户返回 version=0 的冷启动空画像。导入新事件或维护重算可生成新版本；过期重算按当前时间移除30天窗口外的兴趣，触发依赖外部调度（包装器只跑一轮，云端 timer 需核对部署记录）。画像记录历史兴趣，不表示相关作品目前仍可推荐，推荐阶段必须重查权限与状态。
- 已支持关闭推荐个性化，但不删除画像、不停止事件采集，尚无客户端控制界面。数据自动清理、用户删除兴趣、运营指标页面、推荐 Agent 和模型评测仍待实现，不据此宣称 US-04/05 全部验收。

## 单次维护命令

服务启动后，可从 intelligence 目录运行以下命令，依次完成有界的事件同步与画像重算：

```powershell
$env:PIXELFLOW_MAINTENANCE_URL='http://127.0.0.1:8001'
# 从私有配置注入 PIXELFLOW_INTELLIGENCE_TOKEN，不把真实密钥写入脚本或命令历史。
.\.venv\Scripts\python.exe -m pixelflow_intelligence.maintenance
```

默认每阶段最多20页，总协作式时间预算120秒，单次网络读写超时不超过30秒。可用 --max-sync-pages、--max-refresh-pages（各1至1000）及 --budget-seconds（1至3600）调整。事件同步未追完时不会进入画像重算，避免把未同步完整的数据标记为新画像。

命令输出一条 JSON 摘要，不输出令牌、上游错误正文或画像内容。status=complete 退出码0；达到分页上限为 incomplete、退出码2；配置、网络、认证、响应格式或时间预算失败为 failed、退出码1。已完成的页不会回滚；摘要统计的是收到并验证成功的响应，网络超时时服务端可能已经提交，重试依赖服务端游标和画像新鲜度保证安全。

重算未完成时摘要中的 refresh_after 保存最后确认成功的用户游标，下次可传 --refresh-after 继续。完成后该值为空，下一轮应从头扫描；游标不是密钥，但包含用户标识，摘要应仅保留在受控运维日志中。事件同步游标始终由服务端持久化，不由命令覆盖。

该命令只运行一轮，不创建 Windows 计划任务或常驻调度器。外部调度应避免重叠启动，监控非零退出码，并在持续超出页数上限时提高上限或安排续跑，不能把“进程正常结束”当成“全部维护完成”。时间预算在请求前检查，httpx 的阶段超时并非整轮硬截止，服务端事务也可能在客户端断开后完成。

地址必须是 HTTP(S) origin；明文 HTTP 仅允许 localhost、127.0.0.1 或 ::1，远程地址必须 HTTPS。禁用重定向和环境代理，TLS 校验保持开启。令牌必须是至少32位可打印 ASCII，且不接受命令行令牌参数。

## 测试

推荐规则离线评测：`.\.venv\Scripts\python.exe -m pixelflow_intelligence.evaluation.recommendation`。固定九组虚构场景，输出热门基线对照、顺序断言和过滤/多样性检查；失败返回非零退出码。详见 [评测口径](src/pixelflow_intelligence/evaluation/README.md)。样例指标不能当作真实用户准确率或 Agent 效果。

```powershell
.\.venv\Scripts\python.exe -m pytest -q
# 先从仓库根目录执行 mvn -f server/pom.xml package
.\.venv\Scripts\python.exe scripts/smoke_pipeline.py
```

冒烟脚本用随机临时密钥和新建的 server/target/analytics-smoke-* 数据库启动 Java/Python，校验点击、停留、点赞、重复同步、取消点赞、身份隔离及三个 Skill 的真实数据推荐预览，结束后停止两个进程。保留日志和测试库，不操作用户业务库，不打印凭据。

真实 MySQL 验收（不建临时 SQLite/H2）：

```powershell
# 需要本机 MySQL 8.4 测试账号，并已 mvn -DskipTests package
.\.venv\Scripts\python.exe scripts\verify_profile_pipeline.py `
    --mysql-port 3307 --mysql-user pf_test --mysql-password <本地测试密码>
```

脚本自建两个 `pf_issue12_*_test` 库，跑通两账号真实事件 → 维护同步/续跑 → 当前与历史画像
→ 偏好链路 → 服务/分析库故障，共 57 项断言（含“JAR 内迁移集合与数据库迁移记录、源码清单一致”），
结束后删除本次创建的库。可在 CI 中复用同一命令（`--artifacts-dir` 指定唯一产物目录）。
环境、断言与限制见 [画像历史偏好真实链路验收](../docs/testing/画像历史偏好真实链路验收.md)。

实现参考 [FastAPI 安全依赖说明](https://fastapi.tiangolo.com/tutorial/security/first-steps/)和 [Python SQLite 事务文档](https://docs.python.org/3/library/sqlite3.html)。当前为本地课程实现，不是可直接公网部署的生产服务。
