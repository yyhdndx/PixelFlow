# Web 管理端

Issue 18 的最小 Vue 管理端：管理员登录、待审核作品预览与通过/驳回、审核记录、用户启停。Agent 轨迹仅预留页面，等待 US-05 提供 Java 查询接口。未实现额外的内容管理和数据大屏。

## 本地启动

使用 Node.js 22.12+（本地验收为 24.19.0）与 npm。先按 [Java 服务说明](../server/README.md) 配置并启动 Java/MySQL，然后运行：

```sh
cd admin-web
npm ci
npm run dev
```

浏览器访问终端显示的地址，默认 `http://127.0.0.1:5173`。Vite 将 `/api` 代理到 `http://127.0.0.1:8080`；Java 如使用其他地址，在 `vite.config.js` 调整开发代理目标。Web 不需要数据库密码、JWT 签名密钥、模型密钥或 Python 地址。

## 管理员初始化

在 **Java 进程**启动前注入 `PIXELFLOW_ADMIN_PASSWORD`，使用符合既有密码要求的本地密码；登录账号为 `admin`。不要把真实密码写入前端配置、文档或版本库。

现有 Java 初始化机制只在 `admin` 不存在时创建管理员；修改环境变量不会重置已有管理员密码，也不需要打开演示数据开关。已有管理员应使用原凭据，不通过本任务修改数据库或新增认证入口。

前端登录后通过 `/api/users/me` 确认 `ADMIN` 角色。普通用户会被拒绝进入工作台，服务端 `/api/admin/**` 的权限校验仍是最终依据。令牌仅保存在当前标签页的 `sessionStorage`，关闭标签页后不保留；退出会调用现有注销接口并清理页面资料。遇到 401 会尝试一次共享令牌刷新，失败后返回登录页；网络失败不会自动重发审核或账号状态写入。

## 构建与测试

```sh
npm test
npm run build
npm run preview
```

`dist/` 为可静态托管的构建产物；路由使用 Hash，无需页面路由回退。正式托管时应将同源 `/api` 转发到 Java，管理入口按既有受控方式访问。开发代理不是生产部署配置，本任务不提供部署脚本或公开管理服务。

## 页面与接口

| 页面 | Java 接口 |
| --- | --- |
| 登录与会话 | `/api/auth/login`、`/api/auth/refresh`、`/api/auth/logout`、`/api/users/me` |
| 审核队列和操作 | `GET /api/admin/posts`、`POST /api/admin/posts/{id}/moderation` |
| 审核留痕 | `GET /api/admin/moderation` |
| 用户列表与启停 | `GET /api/admin/users`、`PUT /api/admin/users/{id}/status` |

审核队列提交时间先显示“—”：当前接口只有作品创建时间，不将其冒充提交时间；后续协商后再决定是否补字段。审核记录正常展示既有 `created_at` 审核操作时间。审核和启停均要求填写理由，以服务端成功响应为准，失败保留错误消息及可用的请求标识。

私有图片使用带 Bearer 的 Java 请求加载为临时 Blob URL，卸载后释放；不将令牌放进图片 URL，不将凭据发送给外部素材站点。文本通过 Vue 文本绑定展示，不渲染用户 HTML。

本地验证结果、账号演示顺序和仍待 Android 设备验证的事项见 [后台审核 Web 管理端验收](../docs/testing/后台审核Web管理端验收.md)。
