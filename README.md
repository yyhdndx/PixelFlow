# PixelFlow 智能社区

一个图文 / 视频创作与分享社区：浏览推荐内容、发布作品、互动交流，还能借助 AI 助手完成创作与检索。

## 社区功能

### 浏览与发现

- **信息流**：首页浏览图文 / 视频作品，支持视频播放、多图切换、话题标签；
- **个性化推荐**：根据你的点赞、浏览、停留行为推荐感兴趣的内容，每条都标注"为什么推荐给你"；
- **图片秒开**：预加载 + 多级缓存，滑到哪里图就加载到哪，不卡顿；
- **智能搜索**：用一句话提问（比如"推荐几家咖啡店"），AI 检索站内作品并综合回答；
- **长文摘要**：长文一键压成摘要，快速了解内容。

### 发布与创作

- **发布作品**：标题 + 正文 + 标签，支持多图与视频；
- **草稿**：自动保存，随时继续编辑，不丢失；
- **AI 辅助创作**：AI 帮你起草标题 / 正文 / 标签，流式预览、一键采用，并做内容安全校验；
- **Copilot 助手**：在任意页面唤起 AI 对话，生成内容提案，帮你整理思路。

### 互动与社交

- **点赞 / 收藏**：喜欢就点赞、收藏，随时可取消；
- **评论 / 回复**：两级评论，回复挂在对应评论下；
- **关注**：关注喜欢的作者，看 TA 的最新动态，也积累自己的粉丝；
- **通知**：有人评论 / 回复你时收到提醒，点开自动已读。

### 审核与安全

- 作品发布前经**人工审核**，AI 辅助给出风险提示；
- 内置**内容安全校验**，违规内容拦截；
- 支持**屏蔽作者 / 不感兴趣**，让推荐更懂你。

### 体验细节

- 深色模式、字号调节、触感反馈等贴心的交互细节。

## 技术栈

Android / Web 客户端 + Java（Spring Boot）+ Python（FastAPI）；数据存储用 MySQL，缓存用 Caffeine，限流用 Redis，AI 能力接 DeepSeek。

## 系统架构

```text
客户端（Android / Web） → Java 业务服务 → Python 智能服务（推荐 / Agent）
```

- Java 负责账号、作品、互动与审核；
- Python 负责画像、推荐与 AI 能力；
- AI 遵循「模型提案、系统校验、人保留最终控制」，不代用户发布。

## 项目结构

```text
app/           Android 客户端
server/        Java 业务服务
intelligence/  Python 智能服务
web-app/       Web 用户端
admin-web/     Web 管理端
docs/          需求、架构与验收文档
```

## 快速开始

- **Android**：`.\gradlew.bat :app:assembleDebug`
- **Java**：`cd server && mvn package && java -jar target/pixelflow-server-0.1.0.jar`
- **Python**：`cd intelligence && python -m venv .venv && .\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt`
- **Web**：`cd web-app && npm ci && npm run dev`

各模块细节见对应目录下的 README；环境准备与建库见 [后端开发上手指南](docs/后端开发上手指南.md)。

## 许可证

[MIT](LICENSE)
