# Web 用户端

Vue 用户端：游客可浏览公开作品流与详情；登录后可进行互动、创作、查看和编辑个人资料。与 `admin-web` 同栈（Vue 3 + Vite + Vue Router）。

## 本地启动

使用 Node.js 22.12+（与 admin-web 同版本）与 npm。先按 [Java 服务说明](../server/README.md) 配置并启动 Java/MySQL，然后：

```sh
cd web-app
npm ci
npm run dev
```

浏览器访问终端显示的地址，默认 `http://127.0.0.1:5173`。Vite 将 `/api` 与 `/feed` 代理到 `http://127.0.0.1:8080`；Java 如使用其他地址，在 `vite.config.js` 调整开发代理目标。

## 构建

```sh
npm run build
npm run preview
```

`dist/` 为可静态托管的构建产物；路由使用 Hash，无需页面路由回退。正式托管时应将同源 `/api` 与 `/feed` 转发到 Java。开发代理不是生产部署配置。

## 功能与接口

| 页面/能力 | Java 接口 |
| --- | --- |
| 登录 / 注册 | `/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`、`/api/auth/logout`、`/api/users/me` |
| 首页瀑布流（推荐 / 关注） | `/feed/`、`/api/feed/following` |
| 作品详情 | `/api/posts/{id}` |
| 点赞 / 收藏 | `PUT /api/interactions/likes`、`PUT /api/interactions/favorites` |
| 评论 / 回复 / 删除 | `GET|POST /api/posts/{id}/comments`、`DELETE /api/comments/{commentId}` |

## 新增浏览路由

路由采用 Hash，例如 `http://127.0.0.1:5173/#/search?q=咖啡`。

| 路由 | 内容与接口 | 登录要求 |
| --- | --- | --- |
| `/search?q=` | 标题/话题搜索，`GET /api/search/posts` | 游客可用 |
| `/me` | 本人资料编辑、头像上传/清除、统计，`/api/users/me`、`/api/users/me/avatar`、`/api/users/me/stats` | 需登录 |
| `/me/works`、`/me/works/new`、`/me/works/:id/edit` | 作品状态、草稿创建/编辑/提交审核/撤回，`/api/posts`、`/api/media` | 需登录 |
| `/me/favorites` | 收藏作品，`GET /api/users/me/favorites` | 需登录 |
| `/me/following`、`/me/followers` | 分页查看关注与粉丝，`/api/users/me/following`、`/api/users/me/followers` | 需登录 |
| `/me/notifications` | 分类通知、单条已读、屏蔽类型，`/api/users/me/notifications` 与偏好接口 | 需登录 |
| `/me/interests`、`/me/interests/history` | 兴趣画像/历史、个性化开关、恢复屏蔽主题/作者 | 需登录 |
| `/author/:id` | 作者公开资料与作品，`GET /api/users/{id}/profile`、`GET /api/users/{id}/posts` | 既有接口要求登录 |

搜索、收藏、作者作品复用卡片及游标分页，关注名单支持分页；首屏/续页失败均可重试，续页失败保留已加载内容。空白搜索不请求。首页卡片及详情作者名可进入作者页。

详情关注状态使用作者资料的 `is_following`；通过 `PUT /api/interactions/follows` 成功后更新按钮。游客点击跳登录并回到原详情，本人作品隐藏按钮。受保护路由保留登录回跳，退出或会话改变后清空私有列表，忽略旧响应。

当前创作支持图片草稿、编辑媒体顺序、提交审核和撤回；提交审核后并非直接发布。头像与图片上传按接口限制 JPEG/PNG 和每张 8 MiB。当前尚未提供私信、AI 搜索/总结/文案/回复规则等 Android 入口；这些流程不能视为已对齐。新增写入流程仅使用本地模拟接口验收，未对真实远程账号执行写入验证。

截图与实际联调边界见 [浏览闭环验收](../docs/testing/Web用户端浏览闭环验收-20261105.md)。

## 说明

- 令牌保存在当前标签页 `sessionStorage`，遇到 401 会尝试一次共享刷新，失败回到登录页。
- 游客可浏览首页与详情、阅读评论；写操作（点赞/收藏/评论/回复）会引导登录。
- 文本通过 Vue 文本绑定展示，不渲染用户 HTML；话题标签做高亮展示。
- 不引入任何后端契约变更，也不在 Java 端新增 CORS 白名单（开发期走 Vite 同源代理）。
