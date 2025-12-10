# PixelFlow - 简易版可颂 APP

<div align="center">

![Android](https://img.shields.io/badge/Android-24+-green.svg)
![Kotlin](https://img.shields.io/badge/Java-8-orange.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

一个功能完整、体验流畅的 UGC 社区应用，支持双列瀑布流、图文详情、内容创作等功能。

[功能特性](#功能特性) • [技术栈](#技术栈) • [快速开始](#快速开始) • [项目结构](#项目结构) • [开发文档](#开发文档)

</div>

---

## 📖 项目简介

PixelFlow 是一个基于 Android 开发的 UGC（用户生成内容）社区应用，旨在满足普通人的内容创作与交流需求。产品形态以双列图文视频混排为主，通过双列布局提升屏效比，帮助用户更快速地进行内容消费与筛选。

### 业务背景

抖音图文业务的目标是建设一个能满足好普通人的内容创作与交流的 UGC 社区，解决好用户的经验决策、圈层交流类需求。UGC 强调内容的真实多元、作者的第一人称表达，相较于 PGC 类内容，更加多元，由普通人分享创作。

### 产品特点

- **双列瀑布流**：一屏展示多个作品，屏效比更高
- **图文为主**：信息密度高、可控感强，帮助用户快速消费与筛选
- **低门槛创作**：图文体裁内容创作成本低，利于普通用户的低门槛创作

---

## ✨ 功能特性

### 核心功能

- ✅ **双列瀑布流列表**
  - 使用 `StaggeredGridLayoutManager` 实现双列布局
  - 支持下拉刷新、上拉加载更多
  - 智能预加载，提升用户体验
  - 支持图片、视频混排展示

- ✅ **作品详情页**
  - 无缝转场动画（从列表到详情）
  - 支持图片/视频轮播（ViewPager2）
  - 手势交互（拖拽退出）
  - 点赞、关注、分享功能

- ✅ **内容创作**
  - 支持标题、描述、话题标签输入
  - 支持图片/视频多选
  - 媒体预览功能
  - 实时字符计数

- ✅ **话题标签**
  - 点击话题跳转到话题页
  - AI 生成话题介绍（集成 DeepSeek API）
  - Markdown 格式内容渲染
  - 相关话题推荐

- ✅ **个人中心**
  - 用户资料编辑（头像、昵称、简介）
  - 统计数据展示（作品数、粉丝数、关注数）
  - 设置功能

### 增强功能

- 🚀 **性能优化**
  - 多优先级智能预加载策略
  - 图片加载优化（Glide 缓存）
  - 内存管理（LRU 缓存、内存回调）
  - 线程池管理

- 🎨 **交互优化**
  - 无缝转场动画
  - 手势交互（拖拽退出详情页）
  - 滚动监听（FAB 按钮智能显示/隐藏）
  - 即时反馈（乐观更新）

- 💾 **数据持久化**
  - Room 数据库存储
  - 点赞、关注状态持久化
  - 用户资料本地存储
  - 通用存储架构（支持多种数据类型）

- 🎵 **多媒体支持**
  - 图片展示（多图轮播）
  - 视频播放（ExoPlayer）
  - 音频播放（背景音乐）
  - 视频封面自动提取

---

## 🛠 技术栈

### 核心技术

| 技术 | 版本 | 用途 |
|------|------|------|
| **Android SDK** | 34 | 目标 SDK 版本 |
| **Java** | 8 | 开发语言 |
| **Gradle** | 8.5.0 | 构建工具 |

### 主要依赖

| 依赖库 | 版本 | 用途 |
|--------|------|------|
| **AndroidX** | - | UI 框架 |
| **Material Design** | 1.9.0 | Material 组件 |
| **Glide** | 4.16.0 | 图片加载 |
| **OkHttp** | 4.12.0 | 网络请求 |
| **Gson** | 2.10.1 | JSON 解析 |
| **Room** | 2.6.0 | 数据库 |
| **ExoPlayer** | 2.16.1 | 视频/音频播放 |
| **Markwon** | 4.6.2 | Markdown 渲染 |
| **PhotoView** | 2.3.0 | 图片查看 |

### 架构模式

- **MVVM + MVC 混合架构**
- **模块化设计**
- **统一数据持久化架构**

---

## 📁 项目结构

```
pixel-flow/
├── app/
│   ├── src/main/
│   │   ├── java/com/PixelFlow/app/
│   │   │   ├── API/                    # 网络请求层
│   │   │   │   ├── ApiClient.java      # API 客户端
│   │   │   │   ├── ApiResponse.java    # API 响应
│   │   │   │   └── PostItem.java       # 作品数据模型
│   │   │   │
│   │   │   ├── ButtonFragment/         # Fragment 页面
│   │   │   │   ├── HomeFragment.java   # 首页（瀑布流）
│   │   │   │   └── ProfileFragment.java # 个人中心
│   │   │   │
│   │   │   ├── Streams/                # 列表相关
│   │   │   │   ├── WaterfallAdapter.java # 瀑布流适配器
│   │   │   │   ├── WorkItem.java        # 作品数据模型
│   │   │   │   └── PreloadManager.java  # 预加载管理器
│   │   │   │
│   │   │   ├── DetailPage/             # 详情页
│   │   │   │   ├── WorkDetailActivity.java # 详情页 Activity
│   │   │   │   ├── ImagePagerAdapter.java  # 图片轮播适配器
│   │   │   │   └── FullScreenMediaDialog.java # 全屏图片对话框
│   │   │   │
│   │   │   ├── Hashtag/                # 话题标签
│   │   │   │   └── HashtagActivity.java # 话题页
│   │   │   │
│   │   │   ├── DAO/                    # 数据持久化
│   │   │   │   ├── AppDatabase.java    # Room 数据库
│   │   │   │   ├── StorageDao.java     # 存储 DAO 接口
│   │   │   │   ├── StorageEntity.java  # 存储实体
│   │   │   │   ├── DataType.java       # 数据类型枚举
│   │   │   │   ├── StorageManager.java # 存储管理器
│   │   │   │   ├── impl/
│   │   │   │   │   └── GenericStorageRepository.java # 泛型存储实现
│   │   │   │   └── PersistState/       # 持久化状态
│   │   │   │       ├── LikeStatus.java  # 点赞状态
│   │   │   │       ├── FollowStatus.java # 关注状态
│   │   │   │       └── Profile.java     # 用户资料
│   │   │   │
│   │   │   ├── MainActivity.java        # 主界面
│   │   │   ├── SplashActivity.java      # 启动页
│   │   │   ├── CreateWorkActivity.java  # 创建作品页
│   │   │   ├── AudioPlayer.java         # 音频播放器
│   │   │   ├── UserDataManager.java     # 用户数据管理
│   │   │   └── util/
│   │   │       └── DateUtils.java       # 日期工具
│   │   │
│   │   ├── res/                         # 资源文件
│   │   └── AndroidManifest.xml         # 应用清单
│   │
│   └── build.gradle.kts                 # 应用构建配置
│
├── gradle/                              # Gradle 配置
│   └── libs.versions.toml              # 依赖版本管理
│
├── UML设计文档.puml                     # UML 类图
├── UML时序图.puml                       # UML 时序图
├── UML架构图.puml                       # UML 架构图
├── UML组件图.puml                       # UML 组件图
├── UML文档说明.md                       # UML 文档说明
└── README.md                            # 项目说明文档
```

---

## 🚀 快速开始

### 环境要求

- **Android Studio**：Hedgehog | 2023.1.1 或更高版本
- **JDK**：8 或更高版本
- **Android SDK**：API 24 (Android 7.0) 或更高
- **Gradle**：8.5.0
- **Android Gradle Plugin**：8.5.0

### 安装步骤

1. **克隆项目**
   ```bash
   git clone <repository-url>
   cd pixel-flow
   ```

2. **配置 API 地址**
   
   编辑 `app/src/main/assets/api.properties` 文件：
   ```properties
   url=https://your-api-url.com
   ```

3. **同步依赖**
   
   在 Android Studio 中打开项目，等待 Gradle 同步完成。

4. **运行项目**
   
   - 连接 Android 设备或启动模拟器
   - 点击 `Run` 按钮或按 `Shift + F10`
   - 等待应用安装并启动

### 构建 Release 版本

```bash
./gradlew assembleRelease
```

生成的 APK 文件位于：`app/build/outputs/apk/release/app-release.apk`

---

## 📱 使用说明

### 主要功能

#### 1. 浏览作品
- 打开应用后，进入首页双列瀑布流
- 上下滑动浏览作品
- 下拉刷新获取最新内容
- 滚动到底部自动加载更多

#### 2. 查看详情
- 点击作品卡片进入详情页
- 左右滑动查看多图/视频
- 点击图片可全屏查看
- 向下拖拽可退出详情页

#### 3. 内容创作
- 点击底部 FAB 按钮进入创作页面
- 输入标题、描述、话题标签
- 选择图片或视频
- 点击保存发布作品

#### 4. 话题探索
- 点击内容中的话题标签
- 查看话题详情和 AI 生成介绍
- 浏览相关话题推荐

#### 5. 个人中心
- 点击底部导航栏"我的"
- 编辑个人资料（头像、昵称、简介）
- 查看统计数据

---

## 🏗 架构设计

### 整体架构

```
┌─────────────────────────────────────┐
│         UI Layer (Activity/Fragment)│
├─────────────────────────────────────┤
│      Adapter Layer (RecyclerView)   │
├─────────────────────────────────────┤
│      Business Logic Layer           │
│  (Manager, Repository, API Client)  │
├─────────────────────────────────────┤
│      Data Layer                     │
│  (Room Database, SharedPreferences) │
└─────────────────────────────────────┘
```

### 核心模块

- **UI 层**：Activity、Fragment 管理用户界面
- **适配器层**：RecyclerView Adapter 处理列表展示
- **业务逻辑层**：Manager、Repository 处理业务逻辑
- **数据层**：Room、SharedPreferences 处理数据持久化

### 设计模式

- **单例模式**：ApiClient、StorageManager、AudioPlayer
- **观察者模式**：数据变更监听
- **策略模式**：预加载优先级策略
- **工厂模式**：GenericStorageRepository 创建

---

## 📚 开发文档

### 技术方案文档

项目包含详细的技术方案设计文档（PDF 格式），涵盖：
- 实现方案/框架介绍
- 完成功能列表
- 个人思考与设计决策
- 性能优化方案
- 遇到的问题与解决方案

---

## 🎯 核心特性详解

### 智能预加载

采用多优先级预加载策略，根据使用场景动态调整：

- **PRIORITY_LOW**：滚动时预加载，低质量版本
- **PRIORITY_MEDIUM**：可视区预加载，中等质量
- **PRIORITY_HIGH**：详情页预加载，高质量版本

### 无缝转场动画

详情页采用无缝转场设计：
1. 截取列表页截图作为背景
2. 图片从卡片位置放大到全屏
3. 背景和内容分阶段淡入
4. 支持手势拖拽退出

### 统一数据持久化

使用 Room 数据库 + 通用存储实体架构：
- 一个 `StorageEntity` 支持所有数据类型
- 通过 `DataType` 枚举区分数据类型
- 使用 JSON 序列化存储任意对象
- 内存缓存 + 数据库双重存储

---

## 🔧 配置说明

### API 配置

在 `app/src/main/assets/api.properties` 中配置 API 地址：

```properties
url=https://your-api-url.com
```

### 权限配置

应用需要以下权限：
- `READ_EXTERNAL_STORAGE`（Android 12 及以下）
- `READ_MEDIA_IMAGES`（Android 13+）
- `INTERNET`（网络请求）

### 数据库配置

数据库名称：`PixelFlowDb`  
数据库版本：1  
支持的数据类型：LIKE_STATUS、FOLLOW_STATUS、FAVORITE、USER_PROFILE、BROWSE_HISTORY、SEARCH_HISTORY、APP_SETTINGS、DRAFT

---

## 🐛 已知问题

- [ ] 相机功能暂未实现（FAB 按钮点击无响应）
- [ ] 作品发布功能为模拟实现（未连接真实服务器）
- [ ] 部分功能（评论、搜索）为预留接口

---

## 🚧 开发计划

- [ ] 完善作品发布功能（图片/视频上传）
- [ ] 实现评论功能
- [ ] 实现搜索功能
- [ ] 实现草稿保存和恢复
- [ ] 实现浏览历史记录
- [ ] 优化网络请求（添加重试机制）
- [ ] 添加单元测试和 UI 测试

---

## 📝 更新日志

### v1.0 (2024)

**核心功能**
- ✅ 双列瀑布流列表页
- ✅ 作品详情页
- ✅ 内容创作页面
- ✅ 话题标签页
- ✅ 个人中心

**增强功能**
- ✅ 智能预加载
- ✅ 无缝转场动画
- ✅ 数据持久化
- ✅ 多媒体支持

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 👥 作者

- **开发者** - [yyhSeu]
- **项目** - PixelFlow

---

## 🙏 致谢

- [Glide](https://github.com/bumptech/glide) - 图片加载库
- [OkHttp](https://square.github.io/okhttp/) - HTTP 客户端
- [ExoPlayer](https://github.com/google/ExoPlayer) - 媒体播放器
- [Room](https://developer.android.com/training/data-storage/room) - 数据库框架
- [Markwon](https://github.com/noties/Markwon) - Markdown 渲染库

---

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- 发送邮件：[213232683@seu.edu.cn]

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给个 Star！⭐**

Made with ❤️ by PixelFlow Team

</div>

