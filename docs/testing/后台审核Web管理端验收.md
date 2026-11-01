# 后台审核 Web 管理端验收

## 范围与基线

- Issue 18，分支 `feature/issue-18-admin-web`。
- 开发基线 `282cdb6`（执行 fetch 后与 origin/master 一致）。本报告对应该基线上的未提交管理端工作区；尚无功能提交号。
- 仅新增 Web 页面、依赖、测试和说明，不修改 Java 业务、OpenAPI、数据库迁移、Android 或部署脚本。
- 按任务负责人确认，提交审核时间先留空，后续协商后决定是否新增；已有审核操作时间正常显示。

## 本地环境与命令

2026-11-01，Windows，Node.js 24.19.0、npm 11.17.0、Java 17.0.12、MySQL 8.4。Vue 3.5.44、Vue Router 4.6.4、Vite 7.3.7、Vitest 5.0.5，确切依赖见 npm 锁文件。

```sh
cd admin-web
npm ci
npm test
npm run build
npm run dev
# 静态产物预览
npm run preview
```

Java 使用当前源码构建：

```sh
mvn -B -ntp -f server/pom.xml -DskipTests package
```

本次该命令仅用于启动联调，Java 测试被显式跳过，不计为 Java 回归通过。未改后端业务，未宣称重跑全部后端测试。

真实 HTTP 联调使用本机独占库 `pf_issue18_web_20261101_test`，由既有 Flyway 正常初始化至 V11；未增加或改写迁移。连接凭据、管理员初始化密码仅注入本地进程，不在文档记录。Java 监听 127.0.0.1:8080，Web 开发服务通过 /api 代理接入。普通用户 A/B 均通过注册/登录接口创建，作品状态全通过 HTTP 变更，没有 SQL 改状态。

## 实际结果

| 检查 | 本地结果 |
| --- | --- |
| 前端自动化 | 3 个测试文件，14 项通过，0 失败、0 跳过；修复后重新运行 npm test 与 npm run build 均成功 |
| 生产构建 | npm run build 成功 |
| 静态托管 | npm run preview 的登录页通过内置浏览器正常加载 |
| 管理员登录 | 浏览器登录成功，重载后恢复当前标签页会话 |
| 待审核作品 | 中文标题、作者、正文可读，私有图片通过带令牌请求加载 |
| Web 驳回 | PENDING → REJECTED，显示服务端审核理由和时间 |
| 作者修改后再审 | HTTP 修改/重新提交后，Web 审核通过为 PUBLISHED |
| 审核留痕 | 浏览器读取到驳回、通过两条记录，前后状态与理由正确 |
| 访问者 B | 通过后详情为 PUBLISHED，普通 Feed 中出现同一作品 |
| 普通用户越权 | 使用 B 令牌请求 /api/admin/posts 返回 403 |
| Web 用户启停 | 在用户页分别填写理由并实际完成禁用、启用 B；启用后页面状态更新，B 新登录返回 200 |
| 启停与会话 | 禁用后登录 401；已有令牌访问本人接口 401；重新启用后旧令牌仍 401，新登录 200 |
| Android 联动录屏 | 未执行，等待设备联调；不能据此前端/HTTP 验证关闭整项 issue |

测试覆盖：共享一次令牌刷新、刷新失败清理、普通身份拒绝、429 消息/requestId/Retry-After、写失败不提示成功、退出后丢弃迟到响应、用户启停理由及自己账号禁用操作、私有图片 Blob 释放、既有数字视频类型。新增覆盖超过 50 条历史审核记录时继续读取后页、新审核记录增量前置且历史不重复，以及 409 后刷新队列仍保留冲突提示并关闭过期预览。

审核记录保持现有后端接口不变：前端从已读取的最大记录 ID 增量查询，每次按 50 条连续读取至末页，新记录在前。读取失败保留已经读取的游标，后续刷新继续，不将第一页误当全部记录。

`npm audit --omit=dev` 为 0 漏洞。完整依赖审计仍报告测试工具 @vue/test-utils 的 js-beautify/editorconfig/glob/minimatch 链上 4 项 moderate，`npm audit fix` 未消除；不属于生产构建依赖，也未通过强制更换不兼容依赖掩盖结果。

## 可重复演示顺序

所有账号、图片和作品均使用本地测试资源；替换下述占位值，不把真实令牌放入报告。管理员只使用本机或既有受控入口。

1. Java 启动前注入 `PIXELFLOW_ADMIN_PASSWORD`；只在 admin 账号不存在时初始化。启动管理端，在登录页以管理员登录。
2. 作者 A、访问者 B 使用 `POST /api/auth/register` 和 `POST /api/auth/login` 创建/登录；A 向 `POST /api/media` 上传本地 PNG/JPEG，取得 `mediaId`。
3. A 调用 `POST /api/posts`，请求如 `{"title":"城市光影","content":"作品说明","mediaIds":["<mediaId>"]}`；再调用 `POST /api/posts/<post_id>/publish`，预期 PENDING。
4. 管理员刷新 Web 审核队列，打开作品预览，填写理由，点击驳回。预期作品移出队列，审核记录出现 PENDING → REJECTED、理由及审核时间。
5. A 使用 `PUT /api/posts/<post_id>` 修改原作品，再调用 publish。管理员刷新队列，填写理由后通过，审核记录新增 PENDING → PUBLISHED。
6. B 调用 `GET /feed/` 和 `GET /api/posts/<post_id>`，预期同一作品公开可见；B 请求 `GET /api/admin/posts` 预期 403。
7. 管理员在 Web 用户页禁用 B，填写理由确认；B 重新登录或使用原令牌调用业务接口预期 401。重新启用后新登录成功，旧令牌仍不可用。
8. 设备联调时将第 2、3、5、6 步改由 Android 用户端完成，录制作者提交、Web 审核、B Feed 展示的完整视频并登记归档位置。当前未录制，不虚构文件或设备验收结论。

## 待协作事项

- 审核队列的提交时间按约定显示“—”；当前 create_time 是作品创建时间，不能替代提交时间。
- Agent 轨迹仅有占位页面，等待 US-05 的 Java 管理查询接口，不直接访问 Python/MCP。
- 本次不包含云端上线、Android 代码或真实设备录屏。远程 CI 未执行，本表只记录本地结果。
