# Redis 限流验收（issue 16）

## 基线与状态

分支：feature/issue-16-redis-rate-limit。基线 master：88627376228c769d3592eff5b69dd354eb08ff9a。当前为该基线上的未提交工作区；提交后以 git rev-parse HEAD 记录被测提交，不能把基线当成功能提交。

本机 Windows、Java17.0.12、Maven3.10.0、MySQL8.4.13。真实Redis通过127.0.0.1:6379连接，PING返回PONG，INFO server报告redis_version:8.10.3、CYGWIN运行环境。该结果针对本机发行包，不代表已验证Linux Redis7.4。沿用Spring Boot3.5.16管理的starter-data-redis3.5.16、Spring Data Redis3.5.13、Lettuce6.6.0.RELEASE。CI指定真实Redis7.4，其结果另行记录。

|验证|实际状态|
|---|---|
|Java17编译|通过|
|配置与故障补充专项|6运行，0失败，0错误，0跳过；包括实际启动校验、连接超时保留、日志抑制及正常业务降级|
|Java/H2/MySQL完整回归|2026-10-30最终工作区，236运行，0失败，0错误，0跳过；全部MySQL专项注入本机独占_test库|
|真实Redis专项RedisRateLimitIT|8运行，0失败，0错误，0跳过；包含新增HTTP窗口恢复及不受限接口验证|
|带Redis profile的clean verify|BUILD SUCCESS，合计244运行，0失败，0错误，0跳过，耗时3分39秒；替代此前241项结果及未配置MySQL时的99跳过结果|
|远程CI|未运行；工作流已加入真实Redis及强制专项，不将配置交付当作CI成功|
|云端上线/Android界面|未执行，不属于本次本地验收|

## 运行命令

普通回归（不等于Redis专项）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -B -ntp -f server/pom.xml -Dtest=ClientIpResolverTest,RateLimitFailureTest,UploadHttpTest,OpenApiContractTest,BusinessApiTest test
mvn -B -ntp -f server/pom.xml clean verify
```

真实Redis先按 [部署说明](../../deploy/Redis限流部署.md) 准备专属实例，再执行：

```powershell
$env:REDIS_TEST_HOST='127.0.0.1'
$env:REDIS_TEST_PORT='6379'
$env:REDIS_TEST_DATABASE='0'
# 有认证时从私有环境注入 REDIS_TEST_USERNAME / REDIS_TEST_PASSWORD
mvn -B -ntp -f server/pom.xml -Predis-integration clean verify
```

本次完整构建为business、preferences、stats、connections、favorites、public_profile、user_posts、post_idempotency、post_idempotency_restart、migration、migration_v7、post_lifecycle分别创建 `pf_i16_<suite>_<运行时间>_test` 本机库，配置对应MYSQL_TEST_*_URL及仅进程可见的MYSQL_TEST_USER/PASSWORD；不使用日常库、共享库或云端库。凭据不记录在文档。统计取自target/surefire-reports和target/failsafe-reports的JUnit XML。

本机脱敏结果：并发12个无效登录请求分发至两个真实Java实例，合计5个进入原业务返回400、7个429；注册前5次201、第6次429且用户只增加5；上传前5次201、第6次429，另一用户仍可上传；TTL恢复、超限不续期、无TTL修复及连接故障恢复断言均通过。连接拒绝/命令超时放行和关闭开关无连接由RateLimitFailureTest实际通过。

专项使用Failsafe的IT命名，只有显式profile执行；Redis缺失会失败，不静默跳过。CI已将Java命令改为带profile，并保留原MySQL、Python、部署脚本和真实画像链路。MySQL验收仍需上手指南中的全部独立_test库，未配置时旧MySQL套件跳过不算MySQL通过。

## 测试覆盖与隔离

- RedisRateLimitIT：真实Redis+随机HTTP端口；四规则小额度、请求标识和Retry-After/no-store；注册/媒体超限不写入；成功/失败登录都计数；同IP账号组合、不同IP/不同上传身份隔离；并发两Java实例合计5次进入业务。
- Lua真实验证：首次固定窗口、超限不续期、TTL到期恢复、异常永久键补TTL、多维度最长重试时间。恢复测试通过受控TCP代理验证连接失败后重新使用Redis。
- ClientIpResolverTest及HTTP专项：直连伪造头不改变桶；可信代理单值IP、非法/多值头回退、IPv6；忽略X-Forwarded-For。
- RateLimitFailureTest：真实HTTP验证Redis连接拒绝、无响应命令超时后仍可成功注册、登录及浏览；断言客户端连接/命令超时均为150ms；关闭开关时连接探针确认无连接。日志节流期限独立于恢复状态，按整次HTTP请求汇总两个登录维度再判断恢复，短时再次失败不重复警告。该测试不是原子限流通过的证据。
- HTTP短窗口实际经历成功登录→429→持续重试→恢复200，超额期间health、Feed、画像查询原有503（未配置上游）、令牌刷新及退出保持各自契约。RateLimitConfigurationTest通过真实Spring配置绑定断言非法额度导致上下文启动失败。
- 唯一随机pf_test前缀和独立H2内存库；只SCAN/DEL自己的前缀，不调用FLUSHDB/FLUSHALL。测试创建的Java实例、连接及代理finally关闭。数据库业务写入由HTTP完成。

## Android请求/响应与演示

登录示例（占位凭据）：

```bash
curl -i -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"example_user","password":"<本地测试密码>"}'
curl -i -X POST http://127.0.0.1:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"example_user","password":"<本地测试密码>","nickname":"测试"}'
curl -i -X POST http://127.0.0.1:8080/api/media \
  -H 'Authorization: Bearer <access-token>' -F 'file=@<本地PNG路径>'
```

启用后超额示例（requestId每次由服务器生成并与头一致）：

```http
HTTP/1.1 429
Retry-After: 12
Cache-Control: no-store
X-Request-Id: c4f36eb0-a783-43ac-8d70-c359b45292b4
Content-Type: application/json;charset=UTF-8

{"status_code":429,"code":"RATE_LIMITED","message":"请求过于频繁，请稍后重试","requestId":"c4f36eb0-a783-43ac-8d70-c359b45292b4"}
```

Android收到429后展示提示，遵循Retry-After等待，不当作密码错误或上传成功；不需要增加客户端自报用户ID。正常接口响应沿用旧契约。

在隔离环境设额度5和短窗口：先连续5次请求看到原业务结果，第6次429；持续超额不续期，等待后恢复。再启动第二Java进程（不同HTTP端口、同JWT配置/测试库/Redis数据库/规则及命名空间），轮流发请求验证两实例合计5次。断开本次拥有的Redis/受控代理，验证请求仍进业务、降级日志及failureCount增加，恢复后再次429。只操作自己拥有的资源。

本机真实Redis自动化证据已补录；远程CI及云端启用仍未执行。本任务无数据库迁移、会话迁移、Feed缓存或Android修改；后端通过不代替设备验收，上线需显式开启并单独记录。
