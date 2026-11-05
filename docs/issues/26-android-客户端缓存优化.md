# 26：Android 客户端缓存（四级缓存 L1）

状态：2026-11-05 已实现，PR 待提。负责人：yyh（@yyhSEU）。
分支：`feature/android-client-cache`，PR 目标 `dev`。

## 背景

课程《验收标准》「三、技术复杂度」要求缓存（本地 + 分布式）工作原理，工程复杂度要求移动端。本项目四级缓存的 **L1 客户端层**（Android）此前只有 Glide 默认图片缓存与一个记账式预加载缓存，缺少：
- 显式的图片缓存预算与内存回收联动；
- 首页 Feed 的磁盘缓存（冷启动/弱网兜底）；
- 传输层 HTTP 缓存（OkHttp）。

本任务补齐 L1，并对齐服务端公开只读接口的缓存头契约。

## 现状（改动前）

| 维度 | 现状 |
| --- | --- |
| 图片内存/磁盘缓存 | Glide 默认，无 `AppGlideModule`，预算不可调 |
| 内存压力回收 | `PreloadManager` 只清自己的记账缓存，不清 Glide |
| 详情/全屏大图 | 无 `.override`，大图可能按原图解码（OOM 风险） |
| Feed 数据缓存 | 无，冷启动每次重新请求 |
| HTTP 缓存 | OkHttp 未配 `Cache` |
| 服务端缓存头 | 公开只读接口无 `Cache-Control` |

## 改动范围

### 1. 图片缓存预算与解码上限

| 文件 | 改动 |
| --- | --- |
| `GlideConfig.java`（新增） | `@GlideModule` 显式设内存缓存 `maxMemory()/3`、磁盘 250MB |
| `PreloadManager.java` | `onTrimMemory` 在 COMPLETE/MODERATE/BACKGROUND/RUNNING_CRITICAL 补 `Glide.get(context).clearMemory()` |
| `ImagePagerAdapter.java` | 详情页图 `.override(屏宽, 屏高)`，避免原图解码 |
| `FullScreenMediaDialog.java` | 全屏图 `.override(2048, 2048)`，给 PhotoView 定解码上限 |

### 2. Feed 磁盘缓存（秒开 + 弱网兜底）

| 文件 | 改动 |
| --- | --- |
| `FeedCache.java`（新增） | Java 序列化磁盘缓存，按账号 key 隔离，24h TTL，空结果删除旧文件 |
| `HomeFragment.java` | 冷启动/切账号且列表为空时先读缓存展示；首屏成功后写缓存；缓存键用 `username + baseUrl`（跨进程稳定） |

### 3. OkHttp HTTP 缓存（传输层）

| 文件 | 改动 |
| --- | --- |
| `CommunityClient.java` | 新增 `File cacheDir` 构造重载，配 `Cache(50MB)`；登录请求加 `Cache-Control: no-cache` 绕过缓存防串号 |
| `ApiClient.java` | 传入 `getCacheDir()/http-cache` |
| `PostController.java` | `/feed/`、`/api/posts/{id}` 加缓存头 |
| `SearchPostsController.java` | `/api/search/posts` 加缓存头 |

服务端缓存头契约：**游客** `Cache-Control: public, max-age=60` + `Vary: Authorization`；**登录用户** `no-store`（响应含 `is_liked`/`is_favorited` 个性化字段）。

### 4. 文档

- `docs/android/客户端缓存现状与预算.md`：现状盘点、内存估算、合理缓存大小、gap、profile 测量方案。

## 验收标准

- [ ] `./gradlew :app:compileDebugJavaWithJavac` 通过。
- [ ] 详情页大图解码尺寸 ≤ 屏宽×屏高；全屏 ≤ 2048。
- [ ] 内存紧张回调触发 `Glide.clearMemory()`。
- [ ] Feed 冷启动能展示磁盘缓存，弱网/失败不白屏；空结果覆盖旧缓存；缓存 24h 过期。
- [ ] 登录请求不命中游客 HTTP 缓存（`Vary: Authorization` + 客户端 `no-cache` 双保险）。
- [ ] FeedCache 缓存键跨进程重启稳定命中（`username + baseUrl`，不含 sessionVersion）。
- [ ] 现有图片加载回归（瀑布流/详情/全屏/私有图片隔离）不回归。

## 已知边界

- 服务端缓存头只覆盖三个公开只读接口；静态资源（demo-assets/demo-media）的 `max-age` 未加。
- 真机 profile（冷启动耗时、命中率、内存峰值三档对比）待设备验证，见 `docs/android/客户端缓存现状与预算.md` 第 7 节。
