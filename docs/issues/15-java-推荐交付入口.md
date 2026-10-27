# 15：Java 推荐交付入口（F08）

负责人：Java 同学。Android 侧契约与客户端已由 Android 负责人在本仓库完成，本文定义后端交付层，供 Java 同学实现并验收。

## 背景

Python 已有确定性推荐预览流水线 `GET /recommendations/me/preview`（见 [python-analytics.md](../../contracts/http/python-analytics.md)），返回最多 10 条推荐：`post_id`、`author_id`、`topics`、`score`、`components`、`reason` 等，但**不含完整作品卡片**（标题、媒体 clips、点赞/收藏态等），且当前**没有 Java 推荐代理或 Android 首页接入**。Android 需要「经 Java」的推荐交付入口，把推荐结果补全为可渲染的公开卡片。

## 交付

新增 `GET /api/recommendations/me/feed`，契约见 [java-api.yaml](../../contracts/http/java-api.yaml) 的 `RecommendationFeed` / `RecommendationPost`。要点：

- 仅认证本人（Bearer），身份由既有 `/api/users/me` 校验；忽略任何 user_id 查询参数。未登录 401。
- 单页、最多 10 条、**不分页**；Cache-Control: no-store。
- 调用 Python `GET /recommendations/me/preview`（使用用户 access token），拿到推荐 `post_id` 列表及每条 `reason`/`score`。
- 把推荐 `post_id` 补全为完整公开卡片：复用公开 Feed 的卡片构建逻辑（`Post` schema，含 title/content/clips/media_ids/author/hashtag/music/like_count/is_liked/is_favorited），只返回仍 PUBLISHED 且作者启用的作品；复核中消失或字段变化的一律丢弃，不补位。
- 每条卡片附带 `rec_reason`（推荐理由，可空）与 `rec_score`（6 位小数，可空）。
- 顶层透传 Python 的 `strategy`（personalized/popular）、`reason`、`profile_version`、`preferences_changed`；`preferences_changed=true` 时 `post_list` 为空，客户端可重新请求。
- Python 未配置、超时或不可用返回 503；不退回未经复核的缓存推荐。

## 边界与验收

- 这是确定性 Skill 流水线，不是 Agent：不调用模型、不动态规划、不写互动或曝光事件。
- 候选只覆盖最近 50 条公开作品；公开状态与偏好复核都是快照，客户端打开作品仍需 Java 再次鉴权。
- 独立测试文件：模拟 Python 预览返回（含空候选、偏好版本变化、Python 503），验证卡片补全、可见性过滤、401 与 503 语义，以及不向未登录请求泄露推荐。
- 不新增 Flyway 迁移；不改共享认证逻辑。OpenAPI 契约本任务已由 Android 负责人补好，实现时以该契约为准，无需重复编辑。
