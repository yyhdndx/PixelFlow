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

## 新增浏览路由

路由采用 Hash，例如 `http://127.0.0.1:5173/#/search?q=咖啡`。

| 路由 | 内容与接口 | 登录要求 |
| --- | --- | --- |
| `/search?q=` | 标题/话题搜索，`GET /api/search/posts` | 游客可用 |
| `/me` | 当前账号昵称、头像，`GET /api/users/me` | 需登录 |
| `/me/favorites` | 收藏作品，`GET /api/users/me/favorites` | 需登录 |
| `/me/following` | 只读关注名单，`GET /api/users/me/following` | 需登录 |
| `/author/:id` | 作者公开资料与作品，`GET /api/users/{id}/profile`、`GET /api/users/{id}/posts` | 既有接口要求登录 |

搜索、收藏、作者作品复用卡片及游标分页，关注名单支持分页；首屏/续页失败均可重试，续页失败保留已加载内容。空白搜索不请求。首页卡片及详情作者名可进入作者页。

详情关注状态使用作者资料的 `is_following`，不遍历关注名单；通过 `PUT /api/interactions/follows` 成功后更新按钮。游客点击跳登录并回到原详情，本人作品隐藏按钮。受保护路由保留登录回跳，退出或会话改变后清空新页面的私有列表，忽略旧响应。不提供资料编辑、发布、通知或推荐偏好功能。

截图与实际联调边界见 [浏览闭环验收](../docs/testing/Web用户端浏览闭环验收-20261105.md)。

## 说明

- 令牌保存在当前标签页 `sessionStorage`，遇到 401 会尝试一次共享刷新，失败回到登录页。
- 游客可浏览首页与详情、阅读评论；写操作（点赞/收藏/评论/回复）会引导登录。
- 文本通过 Vue 文本绑定展示，不渲染用户 HTML；话题标签做高亮展示。
- 不引入任何后端契约变更，也不在 Java 端新增 CORS 白名单（开发期走 Vite 同源代理）。
