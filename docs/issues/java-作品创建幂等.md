# Java 作品创建幂等验收

2026-10-25，基线 `0c82152`，分支 `feature/post-create-idempotency`。本次只交付后端创建接口；不能据此关闭 Android F04。功能分支已建立远程跟踪；实现尚未提交/推送，未部署。

## 接口与客户端

`POST /api/posts` 的可选 `Idempotency-Key` 必须为单个严格 UUID（大小写等价）；空白、空值、重复和逗号多值400 `INVALID_ARGUMENT`。无键保持每次201。有键按认证用户隔离，首次201，原键同规范 Draft 重试200完整当前作品；不同 Draft 为409 `IDEMPOTENCY_CONFLICT`。无效/失效认证401，不占键；原作品不存在404 `POST_NOT_FOUND`，不补建。

文本不trim；clips/mediaIds省略、null、空数组等价，媒体ID、顺序和clip的type/width/height/url参与比较，JSON字段顺序不参与比较。重放比较最初请求指纹，返回后来编辑的内容或PENDING等当前状态，不重新校验原媒体、不恢复旧附件、不重置状态。

```http
POST /api/posts
Authorization: Bearer <access-token>
Idempotency-Key: 123e4567-e89b-12d3-a456-426614174000
Content-Type: application/json

{"title":"旅行","content":"正文","mediaIds":[]}
```

首次：`201`，完整作品中 `post_id="<id>"`、`status="DRAFT"`。相同头与正文再次调用：`200`，同一个ID的完整当前作品。把title改为“另一作品”仍使用此键：

```http
HTTP/1.1 409
Content-Type: application/json

{"status_code":409,"code":"IDEMPOTENCY_CONFLICT","message":"同一 Idempotency-Key 不能用于不同的创建请求","requestId":"<request-id>"}
```

Android调用示意（仅文档，无Android代码变更）：

```kotlin
// 一次逻辑创作开始时生成，并持久保存key和原始Draft。
val key = UUID.randomUUID().toString()
localDraftStore.save(key, draft)
val response = api.createPost(idempotencyKey = key, draft = draft)
// 超时或响应丢失：从本地恢复同一key和Draft重试，201/200都读取post_id。
// 取得ID后转用PUT编辑和publish提交；用户明确新建另一作品时才换key。
```

`localDraftStore`、`api`为说明用途的占位接口，不新增依赖。

## 数据与迁移

双库 `V9__post_creation_idempotency.sql` 新增 `(user_id,request_key)` 主键、SHA-256指纹与BIGINT作品ID。专用服务在一个事务内先锁用户，再锁定读取成功记录；新请求复用PostService.create，最后插入记录。媒体关联、作品与记录一同提交/回滚。无Map、无自动过期、无作品级联删除、无生产故障注入。

V8由头像分支占用，本分支不制造占位、不改Flyway配置。正式合并/发布须协调真实V8先于V9；已经执行V9后再补V8会受默认版本顺序限制，不能用out-of-order或repair掩盖。自动测试验证本分支现有V7直接升级V9，旧作品和关联均保留；尚未验证未合入的真实V8。

## 本地验证结果

JDK17（`C:/Program Files/Java/jdk-17`），本机MySQL `SELECT VERSION()` 再次确认为 `8.4.13`。此前专项22/22使用 `pf_idem_20261025_7f4c_test`、`pf_idem_restart_20261025_7f4c_test`，证据保留；最终全量verify另建下列11个唯一专属库，未复用或删除已有库，未访问业务库或云端。全部URL及凭据仅注入同一个Maven进程，不改全局用户环境。

| 环境变量 | 最终全量验收库名（均在127.0.0.1:3306） |
| --- | --- |
| MYSQL_TEST_URL | pf_business_20261025_67dd3ea7_test |
| MYSQL_TEST_PREFERENCES_URL | pf_preferences_20261025_67dd3ea7_test |
| MYSQL_TEST_STATS_URL | pf_stats_20261025_67dd3ea7_test |
| MYSQL_TEST_CONNECTIONS_URL | pf_connections_20261025_67dd3ea7_test |
| MYSQL_TEST_FAVORITES_URL | pf_favorites_20261025_67dd3ea7_test |
| MYSQL_TEST_PUBLIC_PROFILE_URL | pf_public_profile_20261025_67dd3ea7_test |
| MYSQL_TEST_USER_POSTS_URL | pf_user_posts_20261025_67dd3ea7_test |
| MYSQL_TEST_POST_IDEMPOTENCY_URL | pf_idempotency_20261025_67dd3ea7_test |
| MYSQL_TEST_POST_IDEMPOTENCY_RESTART_URL | pf_idempotency_restart_20261025_67dd3ea7_test |
| MYSQL_TEST_MIGRATION_URL | pf_migration_20261025_67dd3ea7_test |
| MYSQL_TEST_MIGRATION_V7_URL | pf_migration_v7_20261025_67dd3ea7_test |

凭据不记入文档。各套件不能共库；两个迁移目标必须为空，重启测试会清理其专属库。上述库保留供核查。

在server目录执行：

```bash
mvn -B -ntp -Dtest=PostIdempotencyApiTest,MySqlPostIdempotencyApiTest,PostIdempotencyRestartTest,MySqlPostIdempotencyRestartTest test
mvn -B -ntp verify
```

| 套件 | 运行 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| PostIdempotencyApiTest (H2) | 10 | 10 | 0 | 0 |
| MySqlPostIdempotencyApiTest (8.4.13) | 10 | 10 | 0 | 0 |
| PostIdempotencyRestartTest (H2文件) | 1 | 1 | 0 | 0 |
| MySqlPostIdempotencyRestartTest (8.4.13) | 1 | 1 | 0 | 0 |
| OpenApiContractTest | 1 | 1 | 0 | 0 |
| MySqlBusinessApiTest | 36 | 36 | 0 | 0 |
| MySqlFavoritePostsApiTest | 10 | 10 | 0 | 0 |
| MySqlMigrationTest | 2 | 2 | 0 | 0 |
| MySqlPreferenceApiTest | 9 | 9 | 0 | 0 |
| MySqlPublicUserProfileApiTest | 5 | 5 | 0 | 0 |
| MySqlUserConnectionsApiTest | 8 | 8 | 0 | 0 |
| MySqlUserPostsApiTest | 8 | 8 | 0 | 0 |
| MySqlUserStatsApiTest | 12 | 12 | 0 | 0 |
| verify总计（含其余H2及HTTP回归） | 224 | 224 | 0 | 0 |

此前专项命令22/22通过、零跳过。最终在全部11个Java URL已配置的同一进程执行 `mvn -B -ntp verify`，2026-10-25 17:13:48完成，耗时03:03，BUILD SUCCESS，可执行JAR打包成功。未修改生产配置、pom、测试安全开关或断言。此前220项报告包含86项未配置MySQL跳过；本次全部实跑无跳过。总数224而非220是因为启用MySqlBusinessApiTest后实际运行36项，整类禁用时此前报告32项跳过。

最终全量日志：`server/target/post-idempotency-all-mysql-verify.log`；标准报告：`server/target/surefire-reports/`。此前专项22/22证据日志仍在 `server/target/post-idempotency-test.log`；`server/target/post-idempotency-verify.log`为此前仅配置专项库的历史运行，不作为最终全量结果。

### 本机PowerShell复现

从仓库根目录运行；只使用自己的本机MySQL 8.4账号，账号须具备新测试库的建库及读写权限。每次生成新随机后缀并新建11个独立 `_test` 库，尤其migration、migration_v7必须是空库。不要复用上表已验收库，不指向开发/业务/云端库；不要为重跑删除已有库。任何建库失败立即停止，不使用 `IF NOT EXISTS` 掩盖重名。

```powershell
$mysql = 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe'
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:MYSQL_TEST_USER = Read-Host '本机测试数据库用户名'
$securePassword = Read-Host '本机测试数据库密码' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$urlVariables = [ordered]@{
    MYSQL_TEST_URL = 'business'
    MYSQL_TEST_PREFERENCES_URL = 'preferences'
    MYSQL_TEST_STATS_URL = 'stats'
    MYSQL_TEST_CONNECTIONS_URL = 'connections'
    MYSQL_TEST_FAVORITES_URL = 'favorites'
    MYSQL_TEST_PUBLIC_PROFILE_URL = 'public_profile'
    MYSQL_TEST_USER_POSTS_URL = 'user_posts'
    MYSQL_TEST_POST_IDEMPOTENCY_URL = 'idempotency'
    MYSQL_TEST_POST_IDEMPOTENCY_RESTART_URL = 'idempotency_restart'
    MYSQL_TEST_MIGRATION_URL = 'migration'
    MYSQL_TEST_MIGRATION_V7_URL = 'migration_v7'
}
try {
    $env:MYSQL_TEST_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:MYSQL_PWD = $env:MYSQL_TEST_PASSWORD
    $version = & $mysql --host=127.0.0.1 --port=3306 "--user=$env:MYSQL_TEST_USER" --batch --skip-column-names -e 'SELECT VERSION();'
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^8\.4\.') { throw '需要可连接的本机MySQL 8.4' }
    $suffix = (Get-Date -Format 'yyyyMMdd') + '_' + [Guid]::NewGuid().ToString('N').Substring(0,8)
    foreach ($entry in $urlVariables.GetEnumerator()) {
        $database = "pf_$($entry.Value)_${suffix}_test"
        & $mysql --host=127.0.0.1 --port=3306 "--user=$env:MYSQL_TEST_USER" --batch -e "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;"
        if ($LASTEXITCODE -ne 0) { throw "建库失败：$database；已建库保留，不删除" }
        [Environment]::SetEnvironmentVariable($entry.Key, "jdbc:mysql://127.0.0.1:3306/$database", 'Process')
        Write-Host "$($entry.Key) -> $database"
    }
    Push-Location server
    try {
        mvn -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'verify失败，请检查target/surefire-reports' }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $securePassword.Dispose()
    Remove-Item Env:MYSQL_PWD, Env:MYSQL_TEST_PASSWORD, Env:MYSQL_TEST_USER -ErrorAction SilentlyContinue
    foreach ($name in $urlVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
```

密码只进入当前终端及其子进程环境，不输出或保存，不设置用户/系统全局环境；测试后关闭该专用终端。测试库不自动删除，后续清理由库所有者单独确认。

双库专项分别验证：6线程同键同内容恰好1个201和5个200；6线程两种内容恰好1个201、2个200、3个409，最终仅一份作品/媒体关联/记录。测试spy在实际作品、媒体关联和成功记录写入后抛出异常，HTTP500且三者均回滚，原键随后201、200成功。另覆盖媒体归属失败、字段/顺序冲突、null归一、大小写UUID、认证、用户隔离、编辑后原媒体已删除仍可重放、PENDING保持及原作品删除404、数据库唯一约束。

指纹文本按UTF-16长度和逐char编码，保留未配对surrogate，Draft校验与请求语义不变。双库均验证：标题或正文首次为 `?` 后，同键改为JSON转义 `\uD800` 返回409 `IDEMPOTENCY_CONFLICT`，原请求仍可200重放；合法emoji用于标题和正文时同文本重放200、ID及文本一致。

真实HTTP测试模拟客户端丢弃首响应后重试，再关闭整个Spring上下文和HTTP服务、重新启动连接同一数据库，以稳定测试JWT密钥及原Bearer令牌恢复同一ID。日志双库均输出：

```text
POST_IDEMPOTENCY_RESTART vendor=mysql first=201 lost-response-retry=200 restarted-replay=200 same-id=true legacy-post-and-media-preserved=true
POST_IDEMPOTENCY_RESTART vendor=h2 first=201 lost-response-retry=200 restarted-replay=200 same-id=true legacy-post-and-media-preserved=true
```

`git diff --check`通过。CI新增两套件各自独立数据库和环境变量，沿用mysql:8.4；尚未执行远端CI。
