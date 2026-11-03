# Web 用户端

US-09 的最小 Vue 用户端：游客可浏览公开作品流与详情，登录后点赞、收藏、评论与回复。与 `admin-web` 同栈（Vue 3 + Vite + Vue Router），复用其鉴权与令牌刷新封装。

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

## 说明

- 令牌保存在当前标签页 `sessionStorage`，遇到 401 会尝试一次共享刷新，失败回到登录页。
- 游客可浏览首页与详情、阅读评论；写操作（点赞/收藏/评论/回复）会引导登录。
- 文本通过 Vue 文本绑定展示，不渲染用户 HTML；话题标签做高亮展示。
- 不引入任何后端契约变更，也不在 Java 端新增 CORS 白名单（开发期走 Vite 同源代理）。
