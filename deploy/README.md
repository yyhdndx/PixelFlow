# 部署目录

状态：预留目录，暂不提供可运行的 Docker Compose。

- `docker/`：各服务 Dockerfile 和 Compose 配置。
- `database/`：预留部署级数据库脚本。Java 已实现的 Flyway 迁移以 `server/src/main/resources/db/migration/` 为唯一来源，不复制维护两份；种子由服务启动开关控制。

Java 和 Python 现可分别按 server/README.md 与 intelligence/README.md 运行；Web 尚待实现。Python 使用独立分析导出身份拉取事件；Java 画像代理配置 PIXELFLOW_INTELLIGENCE_BASE_URL。暂无自动同步调度及 Compose。数据库及 Python 服务仅用于内部网络，公开部署前需补 TLS、限流、备份与数据保留策略。真实配置由运行环境注入，不提交密钥。
