# PixelFlow 智能社区

PixelFlow 面向图文与视频内容的发布、浏览和交流，计划通过用户画像、推荐 Agent 与可组合 Skill 提供个性化内容发现和创作辅助。

首次领取后端 issue 请先阅读 **[后端开发上手指南](docs/后端开发上手指南.md)**：统一环境、建库、配置模板、启动、测试和交付流程都从这里开始。云端实际运行版本见 [服务器部署记录](deploy/服务器部署记录.md)，不一定等于仓库最新提交。本期只开发 Android、Java 和 Python，不需要运行 Web。

## 项目结构

```text
pixel-flow/
├── app/                  # Android 用户端（Gradle :app）
├── server/               # Java 核心业务服务
├── intelligence/         # Python 数据分析、Agent、Skill 与 MCP
├── admin-web/            # 历史预留目录，本期不开发、不启动
├── contracts/            # 跨服务 API、事件和 Schema 契约
├── docs/                 # 需求、架构与客户端设计资料
├── deploy/               # 部署配置与数据库初始化
├── tests/                # 跨服务测试和固定评测数据
├── gradle/               # Android Gradle Wrapper 与依赖目录
├── build.gradle.kts
└── settings.gradle.kts
```

## 当前版本

服务端默认数据库为 MySQL，Java 业务库与 Python 分析库分开。环境准备、旧 H2/SQLite 导入和备份方式见 [MySQL 部署与迁移](deploy/MySQL部署与迁移.md)。

Android 源码位于 `app/`，已接入注册登录、内存会话与令牌刷新、首页游标分页、服务端点赞、首版浏览遥测及本人画像查询。Java 服务提供账号、Feed、草稿与人工审核、互动、行为事件、只读分析导出及画像代理；Python 已实现增量同步、规则画像，以及画像解读、候选召回、排序过滤三个业务 Skill。推荐预览已接入真实 Java 公开 Feed、候选复核、持久化作品排除及个性化开关，但尚未接入首页；Agent/MCP 仍待实现。客户端发布、关注/收藏、推荐偏好控制及完整设备联调仍待完成。

## Android 开发

使用 Android Studio 打开仓库根目录，配置 Android SDK 后同步 Gradle。保持 `:app` 模块及应用包名不变；后端项目独立管理依赖，不加入 Android Gradle 构建。

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/`。服务地址由 `app/src/main/assets/api.properties` 的 `url` 设置，填写基础 origin，不附带路径，默认 http://10.0.2.2:8080。Java 后端启动见 [server/README.md](server/README.md)；登录、分页和点赞的配置及验收见 [Android 联调说明](docs/android/Java服务联调说明.md)。

话题 AI 入口暂时显示服务未接入提示，后续通过 Java 服务调用智能服务。禁止在 Android 或 Web 客户端配置模型密钥。

## 文档与模块入口

- [软件需求说明](docs/requirements/软件需求说明.md)
- [总体架构与模块边界](docs/architecture/README.md)
- [后续接口与实现方案](docs/architecture/后续接口与实现方案.md)
- [Java 服务](server/README.md)
- [Java 服务端实现说明](docs/server/服务端实现说明.md)
- [社区演示素材与来源许可](docs/server/演示素材说明.md)
- [Python 智能服务](intelligence/README.md)
- [后端开发上手指南](docs/后端开发上手指南.md)
- [跨服务契约](contracts/README.md)
- [Android 设计资料](docs/android/技术方案设计文档.md)
- [Android 代码审查与整改计划](docs/android/代码审查与整改计划.md)
- [Android 端侧性能研究调研与选题建议](docs/android/端侧性能研究调研.md)
- [Android 端侧性能实验方案](docs/android/端侧性能实验方案.md)
- [部署说明](deploy/README.md)
- [测试组织](tests/README.md)

## 协作约定

当前两个前端驱动的后端任务可并行开发：[个人资料读取与编辑](docs/issues/01-java-个人资料读取与编辑.md)和[个人主页统计](docs/issues/02-java-个人主页统计.md)。分工边界及线上发布状态见 [任务索引](docs/issues/README.md)。

按业务 Story 建分支，例如 `feature/us-01-auth`，提交信息说明模块与目的。接口变化先更新 `contracts/`，再同步调用方；业务实现同步补充模块测试和使用说明。密钥、上传内容、数据库文件、构建产物及本地配置不提交。

Java 依赖版本由 `server/pom.xml` 管理，Python 直接依赖由 `intelligence/pyproject.toml` 声明，安装版本由 `intelligence/requirements-lock.txt` 固定。依赖变更同时更新声明、锁定文件和验证结果。

## 许可证

[MIT](LICENSE)

