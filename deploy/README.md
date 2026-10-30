# 部署目录

Redis认证与上传限流的环境变量、可信代理、故障恢复及回滚见 [Redis限流部署](Redis限流部署.md)；默认关闭，说明交付不代表已上线。

日常 issue 开发请先阅读 [后端开发上手指南](../docs/后端开发上手指南.md)。本目录供部署负责人使用，区分当前状态、迁移操作与历史参考。

已提供 [Gitee → GitHub 自动测试与部署](GitHub自动部署.md) 脚本及配置说明。需完成镜像仓库、SSH 凭据和部署账号配置后启用；脚本提交不代表云端已开启自动发布。

服务器已于 2026-10-19 完成 MySQL 8.4.11 切换，运行提交 `4c23e6c`。实际配置、验证结果和备份位置见 [服务器部署记录](服务器部署记录.md)。

当前数据库方案：Java 与 Python 共用一个 MySQL 实例，使用独立数据库和账号。已部署 H2/SQLite 的 Alibaba Cloud Linux 3 服务器请按 [MySQL 部署与迁移](MySQL部署与迁移.md) 升级；旧 CentOS 文档中的文件数据库配置和备份命令不适用于 MySQL。

状态：已提供 [CentOS 单机部署命令](CentOS部署命令.md)，覆盖 CentOS Stream 9 / 10 的版本检查、Java/Python、HTTPS、firewalld、SELinux、定时维护、备份和版本更新。CentOS 7 / 8 等过期版本会停止执行并提示先评估迁移；尚未在目标云服务器验证，暂不提供可运行的 Docker Compose。

- `docker/`：各服务 Dockerfile 和 Compose 配置。
- Java Flyway 迁移位于 `server/src/main/resources/db/mysql/`；`db/h2/` 保留历史测试兼容。Python 分析库由 `analytics/mysql_schema.py` 管理版本；种子由 Java 服务启动开关控制。

Java 和 Python 也可分别按 server/README.md 与 intelligence/README.md 本地运行。本轮部署不包含 Web。Python 使用独立分析导出身份拉取事件；Java 画像代理配置 PIXELFLOW_INTELLIGENCE_BASE_URL。服务代码本身不自带调度器，部署指南使用 systemd timer 调用已有维护命令。数据库及 Python 服务仅用于内部网络；指南定位为团队受限联调，不代表已经满足公开生产部署条件。真实配置由运行环境注入，不提交密钥。
