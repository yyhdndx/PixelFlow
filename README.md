# PixelFlow 智能社区

PixelFlow 面向图文与视频内容的发布、浏览和交流，计划通过用户画像、推荐 Agent 与可组合 Skill 提供个性化内容发现和创作辅助。

## 项目结构

```text
pixel-flow/
├── app/                  # Android 用户端（Gradle :app）
├── server/               # Java 核心业务服务
├── intelligence/         # Python 数据分析、Agent、Skill 与 MCP
├── admin-web/            # Web 管理端
├── contracts/            # 跨服务 API、事件和 Schema 契约
├── docs/                 # 需求、架构与客户端设计资料
├── deploy/               # 部署配置与数据库初始化
├── tests/                # 跨服务测试和固定评测数据
├── gradle/               # Android Gradle Wrapper 与依赖目录
├── build.gradle.kts
└── settings.gradle.kts
```

## 当前版本

本版本完成仓库结构整理。Android 源码位于 `app/`；其余服务为待实现的目录骨架，尚不提供可运行后端、管理页面或 Agent。目录中的说明文件定义后续开发边界，不代表功能已经实现。

## Android 开发

使用 Android Studio 打开仓库根目录，配置 Android SDK 后同步 Gradle。保持 `:app` 模块及应用包名不变；后端和 Web 项目独立管理依赖，不加入 Android Gradle 构建。

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/`。服务地址由 `app/src/main/assets/api.properties` 的 `url` 设置，填写基础地址，不附带 `/feed/`。Feed 需要可用的业务服务；此版本没有启动后端的命令。

话题 AI 入口暂时显示服务未接入提示，后续通过 Java 服务调用智能服务。禁止在 Android 或 Web 客户端配置模型密钥。

## 文档与模块入口

- [软件需求说明](docs/requirements/软件需求说明.md)
- [总体架构与模块边界](docs/architecture/README.md)
- [Java 服务](server/README.md)
- [Python 智能服务](intelligence/README.md)
- [Web 管理端](admin-web/README.md)
- [跨服务契约](contracts/README.md)
- [Android 设计资料](docs/android/技术方案设计文档.md)
- [部署说明](deploy/README.md)
- [测试组织](tests/README.md)

## 协作约定

按业务 Story 建分支，例如 `feature/us-01-auth`，提交信息说明模块与目的。接口变化先更新 `contracts/`，再同步调用方；业务实现同步补充模块测试和使用说明。密钥、上传内容、数据库文件、构建产物及本地配置不提交。

本次目录骨架不固定服务端框架版本；各模块开始实现时再提交依赖文件与锁文件。

## 许可证

[MIT](LICENSE)

