# 首页关注 Tab 验收

基线：`dev` / `8972e28`；功能分支：`feature/us-09-following-tab`。本记录对应提交前工作区，提交号以 PR 为准。

## 行为与范围

首页为「我的关注 / 社区」，首次进入默认关注。通过 Fragment 保存状态恢复上次选择；全新启动默认关注。两个 Tab 分别保存列表、游标、是否还有下一页、错误与滚动位置。切换取消离开的请求，已加载列表不重复请求；刷新仅更新当前 Tab。账号或会话变化清空两份数据，旧请求不能覆盖新账号。

关注使用现有 `GET /api/feed/following`，社区继续使用 `/feed/`，复用原有响应和卡片。游客关注页不发请求，提供登录入口；空关注提供「去社区看看」；网络失败保留原有重试机制。移除首页推荐流调用、模型、横幅及其专用文案；推荐偏好页面不在本任务范围。

未修改 Java、契约、数据库迁移、Python 或部署配置。

## 环境与结果

本地 Windows，JDK 21.0.11 构建 Android；Android SDK 34；`Medium_Phone_API_37.0` 模拟器（Android 17）。真实服务使用 Java 17、MySQL 8.4.13，独占本机 `pf_following_tab_ui_test`，监听 `127.0.0.1:18085`，模拟器通过 `10.0.2.2:18085` 访问。管理员凭据只注入本次进程与测试参数，记录不包含可用凭据。

| 检查 | 本地结果 |
| --- | --- |
| `:app:assembleDebug` | 通过 |
| `:app:testDebugUnitTest` | 68 运行，0 失败，0 跳过 |
| 首页与头像模拟器回归 | 18 运行，0 失败，0 跳过（含双 Tab 分页、重试、重建与账号切换） |
| 真实 HTTP 模拟器联调 `FollowingFeedHttpTest` | 1 运行，0 失败，0 跳过；再次运行截图场景同样通过 |
| 推荐流调用与文案残留检索、`git diff --check` | 通过 |

首轮模拟器回归的游客测试错误地使用不清除会话的 `restore(null, null, null)`，已改为独立游客客户端，后续通过。Gradle 的 `preDebugAndroidTestBuild SKIPPED` 是构建任务状态，不是测试跳过数。

## 命令

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.PixelFlow.app.API.HomeFeedFlowTest,com.PixelFlow.app.API.AvatarRecommendationFlowTest
```

真实联调需先用现有启动配置启动本机 Java 服务：独占 `_test` 库、`PIXELFLOW_SEED=false`、本次随机 JWT secret 和管理员密码；`PIXELFLOW_PUBLIC_BASE_URL=http://10.0.2.2:18085`。不连接共享或云端库。然后运行：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.PixelFlow.app.API.FollowingFeedHttpTest \
  -Pandroid.testInstrumentationRunnerArguments.followingBaseUrl=http://10.0.2.2:18085 \
  -Pandroid.testInstrumentationRunnerArguments.followingAdminPassword="$LOCAL_TEST_ADMIN_PASSWORD"
```

真实测试通过 HTTP 注册随机作者 A、访问者 B，上传生成的小 PNG，创建草稿、提交并批准，同时保留另一份草稿。B 关注 A 后只显示公开作品；B 取关后刷新变为空列表；游客显示登录引导。该测试只接受本机或模拟器宿主地址，未配置服务参数时显式跳过，不能据此声称真实联调通过。测试会创建本轮账号、作品与图片，应使用独占测试库；重跑使用随机账号，可由库拥有者在验收结束后清理整个专用库，不操作其他库。

MockWebServer 流程测试负责分页、失败重试、生命周期、账号切换、双 Tab 列表隔离；真实 HTTP 测试负责真实关注/取关与页面展示。二者结果分别记录，不以 Mock 代替真实接口。

## 截图

以下四图来自模拟器与本机真实服务联调：

- [我的关注](following-tab/following.png)
- [社区](following-tab/community.png)
- [空关注](following-tab/empty-following.png)
- [游客引导](following-tab/guest-following.png)

截图由 `FollowingFeedHttpTest` 写入应用 external files 的 `following-evidence` 目录；Gradle connected 测试结束可能卸载应用，需保留安装并用 `adb shell am instrument` 执行后及时 `adb pull`。

自动化已验证 Activity 重建恢复所选 Tab；物理设备与人工杀进程恢复不在本次模拟器结果之内。未执行云端联调或部署。
