# Web 管理端

状态：目录骨架，没有可启动页面或前端依赖。拟采用 Vue，实施时再创建构建配置与锁文件。

- `src/pages/`：auth、users、content、moderation、agent-traces、analytics 页面。
- `src/api/`：统一 Java API 客户端和按业务组织的请求。
- `src/components/`：共享组件。
- `src/router/`：路由和页面访问控制。
- `src/stores/`：登录态及页面共享状态。
- `public/`：公开静态资源。
- `tests/`：组件与页面测试。

页面权限提示不替代 Java 服务的授权检查。Web 不持有模型密钥，也不直接访问内部 Python 或 MCP 服务。
