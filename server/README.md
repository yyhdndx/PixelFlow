# Java 核心业务服务

状态：目录骨架，尚未配置依赖或实现启动类。拟采用 Spring Boot，服务端 JDK 和框架版本独立于 Android，实施时确定。

源码根目录：`src/main/java/com/pixelflow/server/`。

|业务包|职责|
|---|---|
|auth|注册、登录、令牌、认证与 RBAC|
|users|用户资料、角色与账号状态|
|content|作品、媒体元数据、Feed 与发布|
|interaction|点赞、收藏、关注、负反馈与幂等处理|
|events|行为事件接收、去重与分析数据提供|
|moderation|人工审核、处理记录与管理操作|
|intelligence|调用 Python 服务、请求超时与普通 Feed 降级|
|common|统一错误、请求标识及必要公共配置|

资源放入 `src/main/resources/`，测试放入 `src/test/java/com/pixelflow/server/`。数据库初始化放入根目录 `deploy/database/`。

开发前先在 `contracts/http/` 定义接口。模型和数据库凭据只在服务端运行时注入。不得将 Python 数据处理故障扩散为普通 Feed 故障。
