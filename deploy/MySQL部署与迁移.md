# MySQL 部署与迁移

2026-10-19：现有阿里云服务器已完成 MySQL 8.4.11 迁移和接口验收，见 [服务器部署记录](服务器部署记录.md)。以下保留为迁移流程参考，不要对当前已迁移实例重复建库、导入或覆盖配置。

适用：当前 Alibaba Cloud Linux 3 单机部署，Java 17、Python 3.11、systemd、Nginx。目标使用 MySQL 8.4 LTS。本文命令在云服务器执行，除注明的本地测试外。旧服务器尚未自动迁移；发布这版代码也不会自动导入旧数据。

## 1. 数据库分工

| 服务 | 数据库 / 账号 | 配置 |
| --- | --- | --- |
| Java | pixelflow_app | DB_URL、DB_USER、DB_PASSWORD |
| Python | pixelflow_analytics | ANALYTICS_DB（MySQL URL） |

一个 MySQL 实例，两个独立库和账号。Python 通过 Java 导出 API 同步数据，不跨库读取业务表。MySQL 只监听 127.0.0.1:3306，不开放安全组 3306。Nginx、Java、Python 的端口及 Android 公网地址不变。

代码要求 MySQL 8.0.17+，使用 InnoDB、utf8mb4、区分大小写且不忽略尾空格的 utf8mb4_0900_bin 排序规则；不支持 MySQL 5.7 或用 MariaDB 替代。新服务器选择 8.4 LTS。Java 已加载 MySQL Connector/J 与 Flyway MySQL 模块。Python 使用 PyMySQL，保留 SQLite 仅用于测试和迁移。

## 2. 安装前检查

逐段执行，某段出错时停下检查，不要粘贴进带 `set -e` 的交互式 SSH 会话。

```bash
cat /etc/os-release
rpm -qa | grep -Ei 'mysql|mariadb'
ss -lntp | grep ':3306'
dnf module list mysql
```

如果已有数据库实例，不要重新初始化或覆盖配置。确认没有既有实例后，参考阿里云 Alibaba Cloud Linux 3 安装路线添加 EL8 MySQL 仓库：

```bash
dnf install -y compat-openssl10
dnf install -y https://repo.mysql.com/mysql84-community-release-el8.rpm
dnf repolist --enabled
dnf info mysql-community-server
```

Alibaba Cloud Linux 3 可能没有 mysql 模块：只有模块存在且启用时才执行 `dnf module disable -y mysql`。官方仓库路径中的 `$releasever` 在此系统会展开为 3，应仅在新增的 `/etc/yum.repos.d/mysql-community*.repo` 内把 `/el/$releasever/` 改成 `/el/8/`，并只启用 `mysql-8.4-lts-community`。不要全局更改 releasever。确认候选版本为 8.4 后安装；如果出现源冲突或 GPG 校验失败，应修复源/签名，不跳过校验：

```bash
dnf install -y mysql-community-server
```

编辑 `/etc/my.cnf` 的现有 `[mysqld]` 段，保留已有数据目录等设置，增加或修改：

```ini
bind-address=127.0.0.1
mysqlx-bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_0900_bin
max_allowed_packet=64M
```

```bash
systemctl enable --now mysqld
systemctl status mysqld --no-pager
mysql --version
```

首次安装的官方包通常把 root 临时密码写入 `/var/log/mysqld.log`。只在服务器本地查看，不发送日志中的密码。执行 `mysql_secure_installation` 完成 root 密码设置、删除匿名账号和测试库、禁止 root 远程登录。不要更改其他项目的已有数据库账号。

## 3. 创建两个空库

执行 `mysql -u root -p`，在交互式 SQL 客户端执行以下语句。两个密码占位符必须替换为各自独立的随机密码，不提交 Git；可用密码管理器生成包含大小写、数字、符号的长密码。

```sql
CREATE DATABASE pixelflow_app CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE DATABASE pixelflow_analytics CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE USER 'pixelflow_app'@'localhost' IDENTIFIED BY '<Java数据库密码>';
CREATE USER 'pixelflow_analytics'@'localhost' IDENTIFIED BY '<Python数据库密码>';
GRANT ALL PRIVILEGES ON pixelflow_app.* TO 'pixelflow_app'@'localhost';
GRANT ALL PRIVILEGES ON pixelflow_analytics.* TO 'pixelflow_analytics'@'localhost';
```

账号仅有各自数据库的权限，包含启动建表/升级所需 DDL 权限。不要授予全局权限或创建 `%` 来源账号。验证 TCP 连接账号匹配：

```bash
mysql --protocol=TCP -h 127.0.0.1 -u pixelflow_app -p pixelflow_app -e 'SELECT CURRENT_USER(),VERSION();'
mysql --protocol=TCP -h 127.0.0.1 -u pixelflow_analytics -p pixelflow_analytics -e 'SELECT CURRENT_USER(),VERSION();'
```

## 4. 准备新版本与配置

先把包含本次修改的提交部署到新的 `/opt/pixelflow/releases/<commit>/`。不要修改当前版本目录。在新目录内执行，先切换到平时构建使用的部署账号：

```bash
cd /opt/pixelflow/releases/<commit>/server
mvn clean verify
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/migration-lib
cd ../intelligence
python3.11 -m venv .venv
./.venv/bin/python -m pip install -r requirements-lock.txt
./.venv/bin/python -m pip install --no-deps -e .
./.venv/bin/python -m pytest -q
```

确认 `pixelflow` 用户可读取新版本与虚拟环境。先复制旧环境文件为待切换配置，使用 `cp -n` 避免覆盖已有准备文件：

```bash
cp -n /etc/pixelflow/java.env /etc/pixelflow/java.mysql.env
cp -n /etc/pixelflow/python.env /etc/pixelflow/python.mysql.env
chmod 600 /etc/pixelflow/java.mysql.env /etc/pixelflow/python.mysql.env
```

用编辑器修改 `java.mysql.env` 中以下字段，其余 JWT、服务令牌、地址等保持原值：

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/pixelflow_app?connectionTimeZone=UTC&tinyInt1isBit=true&allowPublicKeyRetrieval=true&sslMode=DISABLED
DB_USER=pixelflow_app
DB_PASSWORD=<Java数据库密码>
PIXELFLOW_SEED=false
```

这里关闭 TLS 只适用于服务器本机回环连接；跨主机部署必须改为验证证书的 TLS 连接。保留 tinyInt1isBit=true，确保 JDBC 返回 Boolean，保持登录、内容可见性和分析导出语义。

修改 `python.mysql.env`：

```dotenv
ANALYTICS_DB=mysql://pixelflow_analytics:<URL编码后的Python数据库密码>@127.0.0.1:3306/pixelflow_analytics
```

URL 中密码若包含 `@ : / # % ?` 等字符必须百分号编码；密码管理器可保存编码后的连接串。Java 的 DB_PASSWORD 不做 URL 编码。这些文件由 systemd 读取，不要直接 `source`：旧 H2 URL 中的分号不符合未加引号的 shell 赋值语法。

## 5. 停写和备份

预约维护时间：迁移和切换期间接口暂不可用。先记录 `readlink -f /opt/pixelflow/current` 的结果。停止已配置的分析定时器（如果存在），再停止两端，防止备份后还有新写入：

```bash
systemctl stop pixelflow-java pixelflow-python
systemctl is-active pixelflow-java pixelflow-python
```

确认均已停止后，创建独立备份目录；将下面目录日期替换为本次迁移时间：

```bash
install -d -m 700 /var/backups/pixelflow/mysql-migration-20261018
cp -a /var/lib/pixelflow/java /var/backups/pixelflow/mysql-migration-20261018/
cp -a /var/lib/pixelflow/python /var/backups/pixelflow/mysql-migration-20261018/
cp -a /etc/pixelflow/java.env /etc/pixelflow/python.env /var/backups/pixelflow/mysql-migration-20261018/
```

当前有效 Java 数据库是 `pixelflow-v1.mv.db`，不是之前遗留的 `pixelflow.mv.db`。保留原文件，不能直接把 H2/SQLite 文件放进 MySQL 数据目录。SQLite 停止服务后连同目录一起备份，包含可能存在的 WAL 文件。

## 6. 导入旧数据

两端工具均要求业务服务停止、目标库为空。Java 导入器先用 Flyway 建表，不运行种子；Python 导入器先建分析表。成功导入后重复执行会拒绝覆盖。失败时数据事务回滚，但 MySQL DDL 不回滚，已创建的表可能保留。不要用 Flyway repair 或清库掩盖失败。

创建权限为 root:root 600 的 `/etc/pixelflow/migrate-java.env`，用编辑器填入：

```dotenv
SOURCE_DB_URL=jdbc:h2:file:/var/lib/pixelflow/java/pixelflow-v1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
SOURCE_DB_USER=<旧java.env中的DB_USER>
SOURCE_DB_PASSWORD=<旧java.env中的DB_PASSWORD>
DB_URL=<java.mysql.env中的DB_URL>
DB_USER=pixelflow_app
DB_PASSWORD=<Java数据库密码>
```

替换命令中的 `<commit>` 后执行；systemd 读取 root 私有环境文件，再以 pixelflow 用户运行，避免把密码放在命令行：

```bash
systemd-run --unit=pixelflow-java-db-import --wait --pipe --collect \
  -p User=pixelflow -p Group=pixelflow \
  -p EnvironmentFile=/etc/pixelflow/migrate-java.env \
  /usr/lib/jvm/java-17-openjdk/bin/java \
  -cp '/opt/pixelflow/releases/<commit>/server/target/classes:/opt/pixelflow/releases/<commit>/server/target/migration-lib/*' \
  com.pixelflow.server.tools.MigrateH2ToMysql
```

导入保留用户 ID、密码哈希、会话、帖子 ID、图片二进制、互动、偏好、审核数据、种子批次、事件来源 ID 与 last_seq。检查每张表的计数，只有进程成功退出才继续。源库必须为当前 V6；其他版本需先确认升级路径。H2 以只读模式打开，原文件不修改。

Python：

```bash
systemd-run --unit=pixelflow-python-db-import --wait --pipe --collect \
  -p User=pixelflow -p Group=pixelflow \
  -p EnvironmentFile=/etc/pixelflow/python.mysql.env \
  /opt/pixelflow/releases/<commit>/intelligence/.venv/bin/python \
  -m pixelflow_intelligence.migrate_mysql \
  --source /var/lib/pixelflow/python/analytics.sqlite3
```

保留事件、画像所有版本、checkpoint 和维护记录。旧 SQLite 中如还存在推荐偏好表，应先按旧偏好迁移文档导入 Java；确认完成后使用 `--legacy-preferences-handled`。默认拒绝忽略这些非空表。如果 Python 尚未产生数据库文件，可跳过导入，新服务会初始化空分析库；不要在已有画像时用空库替代迁移。

## 7. 切换与验收

两端导入成功后才安装新配置，切换 current 符号链接。先确认 `/opt/pixelflow/current` 是符号链接，不是实际目录；下面的 `<commit>` 必须对应已构建的新版本：

```bash
ls -ld /opt/pixelflow/current
install -o root -g root -m 600 /etc/pixelflow/java.mysql.env /etc/pixelflow/java.env
install -o root -g root -m 600 /etc/pixelflow/python.mysql.env /etc/pixelflow/python.env
ln -sfn /opt/pixelflow/releases/<commit> /opt/pixelflow/current
systemctl start pixelflow-java pixelflow-python
journalctl -u pixelflow-java -u pixelflow-python -n 100 --no-pager
```

等待启动完成再检查，不以 systemctl 的 active 单独判断就绪：

```bash
curl --retry 15 --retry-connrefused --retry-delay 2 -fsS http://127.0.0.1:8080/health
curl --retry 15 --retry-connrefused --retry-delay 2 -fsS http://127.0.0.1:8001/health
curl -fsS 'http://127.0.0.1/feed/?count=3&accept_video_clip=true' -H 'Host: 8.140.36.87'
```

再用原测试账号登录，检查 Feed、图片、点赞、偏好和本人画像。使用现有维护命令触发一次同步，确认来源 ID 未变化、游标继续推进。`/health` 是存活探针，并不能代替读写验收。通过后恢复原分析定时器。

回滚：停止新服务，保留 MySQL 数据；恢复备份中的两个旧环境文件，将 current 指回记录的旧版本，再启动。只有切换后尚无新业务写入时，才能直接这样回滚；一旦 MySQL 接收新数据，先处理差异，避免旧库丢失这些新操作。

## 8. 后续备份与开发

MySQL 持久化数据由 mysqld 管理。以后不能只备份 `/var/lib/pixelflow/`。使用具有备份权限的本机账号，以交互密码或私有 option file 认证：

```bash
mysqldump -u root -p --single-transaction --hex-blob --no-tablespaces --set-gtid-purged=OFF \
  --databases pixelflow_app pixelflow_analytics \
  --result-file=/var/backups/pixelflow/mysql-backup.sql
```

备份目录需 root 私有，实际任务使用带时间戳的新文件名，备份成功需确认退出码并定期在独立实例演练恢复。DDL 升级前暂停任务并备份；两端配置和密钥仍单独保存。三人本地开发各用自己的 MySQL 库，不共用云端库跑自动测试。

Java 快速测试仍用 H2；真实 MySQL 测试使用独立空测试库，通过环境变量指定：`MYSQL_TEST_URL`（业务接口）、`MYSQL_TEST_PREFERENCES_URL`（偏好）、`MYSQL_TEST_MIGRATION_URL`（一次性空迁移目标），以及 `MYSQL_TEST_USER` / `MYSQL_TEST_PASSWORD`。这些测试会写入、清理测试数据，禁止指向联调库。运行 `mvn test`。

Python 设置 `MYSQL_TEST_ANALYTICS_DB=mysql://.../pixelflow_analytics_test` 后运行 `python -m pytest -q`；测试库名称必须以 `_test` 结尾。未配置时 MySQL 集成测试明确跳过，不能据此宣称已验证 MySQL。

2026-10-18 本地验证使用已安装的 MySQL 9.0.1 临时独立实例，完成 Java 接口/偏好/迁移测试和 Python 同步/并发重算/导入测试；这不替代云端 MySQL 8.4 的部署验收。当前 Flyway 在 9.0 实例打印版本兼容性提示，未因此跳过测试。

参考：[阿里云 Alibaba Cloud Linux 3 安装说明](https://www.alibabacloud.com/help/zh/ecs/user-guide/deploy-the-lnmp-environment)、[MySQL Yum 仓库](https://dev.mysql.com/downloads/repo/yum/)、[MySQL 建表规则](https://dev.mysql.com/doc/refman/8.4/en/create-table.html)、[Flyway MySQL 模块](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)、[PyMySQL 连接与事务配置](https://pymysql.readthedocs.io/en/latest/modules/connections.html)。
