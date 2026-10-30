# Redis 认证与上传限流部署

本说明仅交付配置方法，不代表已在云端启用。Redis用于临时计数，不保存会话或业务事实。Redis重启/丢失数据可能重置额度，MySQL仍是业务事实来源。

## 运行与网络

使用 Redis 7.4，绑定本机或受控私网地址，禁止公网暴露6379。单机Nginx转发Java时，Redis和Java只监听回环；多Java实例连接同一个Redis数据库并使用相同规则配置和命名空间。不同部署环境使用不同命名空间，例如 pixelflow:staging 和 pixelflow:production。

已有Docker环境可为本地验收启动专属实例：

```bash
docker run --name pf-issue16-redis --rm -d -p 127.0.0.1:6379:6379 redis:7.4
docker exec pf-issue16-redis redis-cli ping
# 仅停止本次拥有的容器
docker stop pf-issue16-redis
```

上述无认证实例仅供回环地址的本地测试。部署时通过Redis ACL配置独立应用身份，密码从私有运行配置注入；不要将密码写入Git、命令行示例或验收日志。允许应用执行脚本及其INCR/PTTL/PEXPIRE命令，并限制可访问命名空间；测试账号另需PING/SCAN/GET/SET/DEL用于验证和清理自己的键。应用不需要FLUSHDB/FLUSHALL权限。

## Java 配置

完整变量见 server/.env.example；应用不会自动加载.env，沿用现有终端/systemd环境注入机制。

|变量|默认值|
|---|---|
|PIXELFLOW_RATE_LIMIT_ENABLED|false，部署必须显式设true才启用|
|PIXELFLOW_RATE_LIMIT_NAMESPACE|pixelflow:local；部署按环境调整|
|PIXELFLOW_RATE_LIMIT_TRUSTED_PROXIES|空，不信任任何代理|
|PIXELFLOW_RATE_LIMIT_LOGIN_IP_LIMIT / WINDOW|100 / 60s|
|PIXELFLOW_RATE_LIMIT_LOGIN_ACCOUNT_LIMIT / WINDOW|10 / 60s|
|PIXELFLOW_RATE_LIMIT_REGISTER_IP_LIMIT / WINDOW|10 / 600s|
|PIXELFLOW_RATE_LIMIT_UPLOAD_USER_LIMIT / WINDOW|30 / 60s|
|REDIS_HOST / PORT / DATABASE|127.0.0.1 / 6379 / 0|
|REDIS_USERNAME / PASSWORD|空，部署注入独立身份|
|REDIS_CONNECT_TIMEOUT / COMMAND_TIMEOUT|300ms / 300ms|

额度必须大于0；窗口为1毫秒到365天，非法规则启动失败。两个实例的额度、窗口、namespace和Redis数据库必须一致。调整规则建议切换明确版本的部署命名空间，避免旧窗口产生歧义。

Lua为每个维度原子计数及设置毫秒TTL，首次尝试开启窗口；成功、失败和超限尝试均计数，后续请求不延长窗口。异常无TTL计数键补上窗口。登录先计IP总量，能解析字符串用户名时再计IP/小写账号；即使IP已超额也继续计账号维度。坏JSON仍计IP总量。不同窗口同时超额取最长剩余时间作为Retry-After。

Key为命名空间、规则v1、接口规则和SHA-256身份摘要；组合身份长度前缀编码。摘要不等于匿名化，不输出完整Key或身份日志。

## Nginx 单跳来源

沿用 deploy/CentOS部署命令.md 的覆盖方式：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
```

本模块只读取X-Real-IP，不解析X-Forwarded-For链。仅当socket来源位于显式可信代理IP/CIDR中，才使用单个合法IP字面量；无效、多值/重复头回退socket。单机可设置 PIXELFLOW_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1/32,::1/128，并确保Java端口不可从外部直连；其他部署只配置实际代理来源。

server.forward-headers-strategy固定配置none，部署不要另行启用Tomcat RemoteIpValve/ForwardedHeaderFilter或覆盖该属性，避免原始socket地址在信任判断前被替换。Nginx原有入口限流是独立保护，演示Java的429时经本机受控Java入口验证，不能把Nginx错误当作本模块证据。

## 故障、恢复与回滚

Redis不可达不阻止Java启动，连接/命令有有限超时；失败时fail-open放行业务并累计本实例failureCount。降级日志每分钟最多一条，包含脱敏累计数；重新成功访问Redis时记录恢复。故障期间限流保护减弱，不能宣称额度始终精确。Redis恢复后客户端自动重连并重新使用计数，无请求内无限重试。

回滚设置PIXELFLOW_RATE_LIMIT_ENABLED=false并重启Java；关闭后不发Redis连接/命令。业务表和会话无需迁移。Redis计数自然过期，不执行清库。启用后先使用小额度在隔离环境验证正常→429→等待→恢复和双实例共享，再由部署负责人记录真实上线证据；本文不执行云端发布。
