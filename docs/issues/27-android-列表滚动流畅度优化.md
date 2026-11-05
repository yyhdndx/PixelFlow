# 27：Android 列表滚动流畅度优化

状态：2026-11-05 实施中。负责人：yyh（@yyhSEU）。
分支：`feature/android-list-perf`，PR 目标 `dev`。

## 背景

首页瀑布流在刷新/加载更多时用 `notifyDataSetChanged()` 全量刷新，整屏闪烁并重新测量；滚出屏幕的图片请求未取消（继续解码，浪费内存/CPU/带宽）；RecyclerView 未做基础优化（无 `setHasFixedSize`、未调 `setItemViewCacheSize`、快速滚动时仍加载图片）。这些是列表卡顿/掉帧的主要来源。

## 现状

| 维度 | 现状 |
| --- | --- |
| 数据刷新 | `WaterfallAdapter.setData()` 用 `notifyDataSetChanged()` 全量刷新 |
| 图片请求回收 | `WaterfallAdapter` / `ImagePagerAdapter` 无 `onViewRecycled` 取消 Glide 请求 |
| RecyclerView 基础 | 无 `setHasFixedSize(true)`、未调 `setItemViewCacheSize` |
| 快速滚动 | 无滚动状态监听，快速滑动时仍逐张解码图片 |

## 改动方案

### 1. DiffUtil 替换 `notifyDataSetChanged`

`WaterfallAdapter.setData()` 用 `DiffUtil.calculateDiff` 计算差异并 `dispatchUpdatesTo(this)`，只更新变化的项（刷新打乱顺序时按 item 移动动画而非整屏闪烁）。按 `id` 判同一项，按 `title/likeCount/isLiked/coverUrl` 判内容是否变化。

### 2. ViewHolder 回收时取消 Glide 请求

`WaterfallAdapter` / `ImagePagerAdapter` 的 `onViewRecycled` 里 `Glide.with(context).clear(imageView)`，滚出屏幕的图片不再解码。

### 3. RecyclerView 基础优化 + 快速滚动暂停图片加载

- `HomeFragment.setupRecyclerView()` 加 `recyclerView.setHasFixedSize(true)`、`setItemViewCacheSize(...)`。
- 滚动监听里加 `onScrollStateChanged`：`SCROLL_STATE_SETTLING`（快速滑动）时 `Glide.pauseRequests`，`SCROLL_STATE_IDLE` 时 `Glide.resumeRequests`。

## 验收标准

- [ ] `./gradlew :app:compileDebugJavaWithJavac` 通过。
- [ ] 刷新/加载更多不再全量 `notifyDataSetChanged`（DiffUtil 差异更新）。
- [ ] 滚出屏幕的 ViewHolder 回收时取消图片请求（`onViewRecycled` → `Glide.clear`）。
- [ ] `setHasFixedSize(true)` + `setItemViewCacheSize` 生效。
- [ ] 快速滚动时暂停图片加载，停止后恢复。
- [ ] 现有首页/详情图片加载回归不破坏（点赞状态、封面、视频封面）。
