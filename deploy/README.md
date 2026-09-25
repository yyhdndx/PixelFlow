# 部署目录

状态：预留目录，暂不提供可运行的 Docker Compose。

- `docker/`：各服务 Dockerfile 和 Compose 配置。
- `database/`：数据库初始化、迁移和演示种子数据。

待 Java、Python 和 Web 分别可运行后，再编写健康检查、服务间网络和启动顺序。数据库及 Python 服务默认只在内部网络开放。真实配置使用被 Git 忽略的环境文件，提交样例时仅包含空值或无效占位符。
