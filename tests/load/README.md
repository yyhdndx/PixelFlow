# Java 非 Agent HTTP 压测

目标分支 `feature/load-test`，PR 提给 `dev`。使用 k6 1.4.2、Java 17、MySQL 8.4 和 Python 3 标准库。所有命令从仓库根目录执行。脚本当前面向 Windows PowerShell，默认 Java 17 位于 `C:/Program Files/Java/jdk-17`。

## 安全边界

仅压本机新建专属 `pf_load_test` 库及由脚本启动的 `127.0.0.1:18096` 服务，不能改成共享服务或生产库。准备前关闭占用该端口的其他服务；脚本不接管已有服务。专用账号只授权该库，不用 root 运行压测。

管理员在本机 MySQL 终端首次创建（密码占位符必须自行替换，不提交）：

```sql
CREATE DATABASE pf_load_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE USER 'pf_load'@'localhost' IDENTIFIED BY '<独立本地密码>';
GRANT ALL PRIVILEGES ON pf_load_test.* TO 'pf_load'@'localhost';
```

首次必须空库。`server/target/load-test/ownership.json` 与库内模板用户共同标记所有权；已有非本工具库会被拒绝。每轮**清空该专属库业务表并恢复固定基线**，保留 Flyway 历史；不要把该库用于其他任务。禁止共享库 FLUSH、清库或公网压测。

## 工具与构建

从 Grafana k6 官方 release 下载固定版本 Windows AMD64 压缩包，解压到 `server/target/load-tools/`，或者通过 `LOAD_TEST_K6` 指定已有 k6。二进制不入库。

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:LOAD_TEST_JAVA_HOME=$env:JAVA_HOME
$env:LOAD_TEST_K6='D:/local_projects/pixel-flow/server/target/load-tools/k6-v1.4.2-windows-amd64/k6.exe'
java -version
mysql --version
& $env:LOAD_TEST_K6 version
mvn -f server/pom.xml package -DskipTests -B
$env:LOAD_TEST_DB_USER='pf_load'
$secret=Read-Host '本地 pf_load 密码' -AsSecureString
$env:LOAD_TEST_DB_PASSWORD=[System.Net.NetworkCredential]::new('', $secret).Password
```

构建命令只打包，跳过 JVM 测试不是压测通过。脚本不读取 `.env`。MySQL 客户端使用子进程 `MYSQL_PWD`，不将密码放命令行；不要共享进程转储或原始环境。

## 数据与执行

```powershell
python tests/load/run.py --prepare-only
python tests/load/run.py --smoke
python tests/load/run.py
```

- 准备：现有 Flyway 正常建表，HTTP 注册一个模板账号，再离线批量生成500用户、2000篇公开作品、每篇20条评论、每用户10关注与25条点赞通知。SQL由确定性算法生成；密码随机。直接预置公开状态只为负载数据，不作为审核链路证据。
- 每轮恢复同一数据，再登录100用户，2 VU预热10秒；正式50只读、100只读、100读写、100只读关闭缓存各300秒。登录有效期15分钟，每轮重新签发。准备登录不计入业务RPS。
- 冒烟将四轮各改为2 VU/10秒，不能代替正式验收。
- 固定VU闭环，无人为sleep；请求返回后发下一次，RPS为实测吞吐，不表示固定到达率。
- 六类读按确定性周期轮换，页面大小20（评论10），Feed/关注/搜索交替首屏和后续页。90%作品详情/评论请求使用10个热点ID，其余散布其余1990篇。
- 混合场景每VU每100次请求前90读、后10写，写包含评论/点赞/收藏；尾部未完成周期可能使实测比例稍偏，需按结果报告。每VU独立账号，点赞收藏轮换active，保留真实业务写入及缓存失效。
- 第一轮后额外10次串行成功登录，单独保存 `login-summary.json`，不混入业务场景指标。

服务固定 `-Xms512m -Xmx1024m`；缓存开关按轮次切换，限流关闭、seed关闭、Python上游为空。预置不含自动回复规则，评论不调用Agent。k6请求白名单排除Agent/internal/admin-agent，访问日志用于复核。图片仅为卡片字段，不请求图片内容，未覆盖媒体吞吐或模型性能。

## 结果与停止

结果在已忽略的 `server/target/load-test/<日期-场景>/`：

- `summary.json`：整体及逐接口延迟、计数、错误率；`points.json`：带endpoint/status/code的原始指标。
- `resources.jsonl`：每5秒Java/MySQL/k6累计CPU秒、working set、private bytes；按相邻累计CPU差/时间差计算占核数，除逻辑核数才是机器CPU百分比。系统权限不允许读取其他服务进程CPU时为null，不能当作0%使用。首轮采样还覆盖额外10次登录，须注明。
- `gc.log`：Java GC暂停与堆信息；`environment.json`：环境、基线commit、JAR与脚本SHA-256，便于区分未提交脚本和被测业务代码。
- `server.log`、`access/`：服务日志、方法/路径/HTTP状态/耗时，不包含Authorization和正文。Windows terminate停止Java可能丢失访问日志尾部缓冲，必须同时核对全量k6状态计数和请求白名单；不能单独以访问日志声称完整审计。
- `run.json`：退出码和是否冒烟；`warmup.json` 不计正式成绩。

```powershell
python tests/load/summarize.py server/target/load-test/<日期-场景>
```

汇总产生 `sanitized-summary.json`：RPS、P50（med）、P95/P99、逐接口错误率、状态码/业务错误码分布及禁止路径计数。错误率包含业务断言失败，不只HTTP错误。k6阈值P95≤800ms且错误率<1%，失败会非零退出，仍保存本轮及继续后续轮次。不得删除失败轮次后宣称全部通过。

正常结束和异常通过finally停止本工具创建的Java与采样进程。Ctrl+C停止运行；若强制结束Python来不及清理，先核对18096对应进程命令行/工作目录，再手动停止该PID，不能批量终止Java。原始目录含短期token、测试密码与基线SQL，仅留本机，不提交/上传。需要重做夹具时由库拥有者确认后删除专属库并重建，同时删除本地 `server/target/load-test/`；不要对其他库执行清理。

## 报告口径

正式结果另存 `docs/testing/压测报告-YYYYMMDD.md`，注明被测commit、机器/OS、Java/MySQL/k6版本、JVM/连接池、数据量、四轮表、认证小样本、错误分布、CPU/内存、瓶颈证据及限制。缓存开启/关闭均为同机独立启动，不能忽略MySQL buffer pool、操作系统缓存、负载机竞争等影响。只说明此数据与并发下是否达标，不能推导生产容量。限流关闭不表示线上不限流。
