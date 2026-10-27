# Claude Code F03 收藏同步独立验收

本文件保留首次独立验收的失败证据，所述代码位置和源码指纹对应修复前快照。后续整改与复验见 [F03 收藏生命周期修复验收](F03-收藏生命周期修复验收-2026-10-25.md)。

首次验收结论：**暂不通过，存在 1 项 P1 阻塞问题。** 收藏状态字段和正常写后回读已经实现，但详情页退出时仍可能丢失用户刚确认的收藏操作，不能将 F03 标记为完成。

测试执行于 2026-10-24，2026-10-25 首次整理时对照隔离快照的 338 个源码/配置文件，工作区尚未发生变化。后续已修改详情页并补正式回归测试，因此以下源码指纹不再代表修复后的当前工作区。主分支已合入基线为 `0c82152`，待验收代码仍是本地未提交改动。

## 验收范围

- Java：详情、Feed、作者作品的 `is_favorited` 字段、访问者归属、OpenAPI 与相关测试。
- Android：`PostItem` / `WorkItem` 状态映射、`CommunityClient.setFavorite` 写后回读、详情页初始状态、正常收藏和退出时的请求处理。
- 没有修改被验收的 F03 业务实现，没有合并、提交、推送或部署。本轮回归测试在隔离副本中添加，保留主工作区其他人的改动。

## 阻塞问题

### [P1] 详情销毁会取消已经确认的收藏写入

位置：[WorkDetailActivity.java](../../app/src/main/java/com/PixelFlow/app/DetailPage/WorkDetailActivity.java)，`toggleFavoriteStatus()` 的写请求使用 `favoriteJob` 保存，而 `onDestroy()` 第 1394 行无条件调用 `favoriteJob.cancel()`。

`CommunityClient` 使用串行队列。如果此时有较慢的其他请求，收藏写入仍在队列里；用户返回或重建页面时，`Job.cancel()` 会把它标为取消，工作线程随后直接跳过。返回列表后的读取排在写入之后，并不能保证被取消的写入执行过。若写入已发送，取消还会使结果变得不确定，不能当作服务器回滚。

实际复现步骤：

1. 打开未收藏作品的详情，等待收藏状态读取完成。
2. 用受控慢请求占用客户端队列。
3. 点击收藏，立即关闭详情页。
4. 放行慢请求，再读取服务器收藏状态。

期望：服务器收到一次收藏写入，返回页面后能读取已更新状态。

实际：服务器收到 **0 次**收藏写入，作品仍未收藏。测试失败信息为 `Leaving detail must keep the queued write expected:<1> but was:<0>`。另一个“留在详情页正常收藏”的对照用例通过，排除了接口整体不可用造成的误判。

修复建议：区分可取消的读取与必须完成并核对的写入；详情销毁只取消读请求，不取消已确认的写入；用页面/会话版本阻止旧回调更新界面，重新进入或返回列表时读取服务端状态。不能只删除取消调用就宣称全部恢复逻辑完成。

整改后至少覆盖：收藏列表 → 详情 → 取消 → 立即返回、队列忙时返回、请求已发出后返回、页面重建、响应丢失后的核对和账号切换。服务器写请求次数与最终状态都需要断言。

本问题由 Android/Claude Code 侧处理，不占用给两位后端同学的 Issue 11/12。

## 验证结果与边界

| 验证 | 结果 |
| --- | --- |
| Android `assembleDebug` | 成功 |
| Android 全部 JVM 单元测试 | 46 项，0 失败、0 错误、0 跳过 |
| Java 受影响的 6 个测试类 | 58 项，0 失败、0 错误、0 跳过 |
| 新增详情设备专项 | 2 项：1 通过、1 失败，0 跳过 |

Java 执行的类：`OpenApiContractTest`、`FavoritePostsApiTest`、`BusinessApiTest`、`UserPostsApiTest`、`UserPostsHttpTest`、`FavoritePostsHttpTest`。这些是 H2/本地 HTTP 专项，不等同于本轮执行了完整 MySQL 8.4 回归。

设备为 Pixel_2_API_36（Android 16/API 36），网络使用设备内 MockWebServer，未访问云端账号或数据。F01/F02 的 11 项设备用例包含列表/资料/偏好等流程，**没有测试详情页收藏写入退出**，因此其通过结果不能证明 F03 的这一条路径正确。

## 复现证据

- [专项测试源码](repros/FavoriteDetailReviewTest.java)
- [设备测试原始 XML](repros/F03-detail-review-result.xml)
- [关键源码 SHA-256](repros/F03-source-sha256.json)

隔离副本：`D:/desk/software_engineering/review-claude-f03-20261024`。该目录是源码快照，不是 Git 分支。完整源文件指纹保存在其 `source-sha256.json`。

在隔离副本中，将专项源码放在 `app/src/androidTest/java/com/PixelFlow/app/API/FavoriteDetailReviewTest.java`，连接测试模拟器后运行：

```powershell
$env:JAVA_HOME = 'D:\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.PixelFlow.app.API.FavoriteDetailReviewTest' --console=plain
```

修复前隔离副本应复现上述一个失败。修复后的正式回归位于 `app/src/androidTest/java/com/PixelFlow/app/API/FavoriteDetailReviewTest.java`。主仓库 `docs/testing/repros/` 下的原始源码只作历史证据，不会自动加入常规构建。

其他日志在隔离副本：`claude-review-android.log`、`claude-review-detail.log`、`server/claude-review-java.log`。后续复跑会覆盖生成报告，因此已将本次关键证据单独保存。

## 后续分工

| 负责人 | 工作 |
| --- | --- |
| Android / Claude Code | 修复本报告的 P1，补详情专项验收后关闭 F03，再继续 F04 的上传/草稿状态恢复 |
| Java 同学 | [Issue 11：草稿创建幂等与重试恢复](../issues/11-java-草稿创建幂等与重试恢复.md)，解决创建已成功但响应丢失时无法安全重试的问题 |
| Python 同学 | [Issue 12：画像历史偏好联调与演示样例](../issues/12-python-画像历史偏好联调与演示样例.md)，验证真实同步、历史和偏好链路，为 F06/F07 提供可复跑证据 |

两份 Issue 已写入仓库 Markdown，尚未发布线上、发送通知或产生新的代码分支。后端两项可并行推进，不需要等待 Android F03 修复；不重复开发已合入的作者作品分页、收藏分页或历史画像代理。
