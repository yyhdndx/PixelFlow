# 阿里云单机部署命令：CentOS

> 历史部署参考：当前项目已改为 MySQL。实际服务器为 Alibaba Cloud Linux 3，请优先使用 [MySQL 部署与迁移](MySQL部署与迁移.md)，不要再次执行本文 H2/SQLite 初始化、配置覆盖和文件数据库备份步骤。

日期：2026-10-18。用途：三人团队共享联调，不是公开生产环境的安全认证。本文根据当前代码整理，未在你的云服务器执行；尤其 Python 锁定依赖此前主要在 Windows 验证，Linux 安装及测试须实际通过。

## 0. 使用前确认

- 本文提供 CentOS Stream 9 / 10 的分支命令，使用 systemd 和单台服务器；尚未确认你机器的具体版本，不把所有 CentOS 当作同一个系统。先执行下面的只读检查。
- 下列 bash 命令都在云服务器的 SSH 终端执行，只有 Android 地址修改在开发电脑完成。
- 使用有 sudo 权限的普通部署账号，不以 root 执行 Maven、pip 或应用。若目前只有 root，可由管理员先执行 useradd -m pfdeploy、passwd pfdeploy、usermod -aG wheel pfdeploy，再 su - pfdeploy 切换账号并确认 sudo -v 可用。不要为方便启用 root 密码 SSH 或修改团队现有登录策略。
- 假定这是新部署，且没有同名服务、配置和数据库。已有部署先备份、审查差异，不覆盖现有配置。
- 准备一个指向服务器公网 IPv4 的域名，以及接收证书通知的邮箱。本文使用 HTTP-01 签发证书，域名必须可通过公网 80 端口访问；有 AAAA 记录时 IPv6 也必须正确，否则先修正 DNS。暂时不要套 CDN。
- 中国内地云资源涉及域名及 App 备案要求，按实际用途先向阿里云确认。本文不以 IP 访问或联调名义规避合规要求。
- 团队成员应有可用的公网出口 IP 白名单；手机流量/校园网出口不固定时，先规划 VPN，不要为了方便直接删除访问限制。
- 先检查磁盘、内存。服务器上构建也需要资源；资源不足时在受控构建机生成版本包，不把关掉测试作为解决办法。

先执行只读检查，不需要 root：

~~~bash
cat /etc/os-release
cat /etc/centos-release
uname -m
~~~

| 检查结果 | 后续处理 |
|---|---|
| CentOS Stream 9 | 使用本文的 9 分支；不要误用系统默认 Python 3.9 |
| CentOS Stream 10 | 使用本文的 10 分支 |
| CentOS Linux 7 / 8、CentOS Stream 8 | 停止后续安装，先备份并规划迁移；不提供切换归档源后公开部署的捷径 |
| Alibaba Cloud Linux、Rocky、AlmaLinux、其他系统 | 不是本文确认过的 CentOS 分支，先按实际系统调整 |

CentOS Linux 7 已于 2024-06-30 结束生命周期，Stream 8 于 2024-05-31 结束构建；旧系统不能仅靠容器或新版 Python 获得宿主机安全维护。本文不会重装你的服务器，也不会自动升级系统。[CentOS 官方公告](https://blog.centos.org/2023/04/end-dates-are-coming-for-centos-stream-8-and-centos-linux-7/)、[CentOS Linux 8 说明](https://www.centos.org/distro-faq/)

安全组中：22 只允许管理员可信出口；80 允许公网证书验证；443 允许团队出口 IP；不要开放 8080、8001 或数据库端口。若启用了 firewalld/其他主机防火墙，需同步配置。不要直接重置已有防火墙或启用一套会锁住 SSH 的规则。

以下变量贯穿同一终端；断开重连后需要重新设置。先执行并按提示填写：

~~~bash
set -euo pipefail
umask 022
. /etc/os-release
if [ "$ID" != centos ] || ! grep -q 'CentOS Stream' /etc/centos-release; then
  printf '%s\n' '停止：仅适用于 CentOS Stream 9 / 10，请先确认系统版本。' >&2
  exit 1
fi
case "$VERSION_ID" in
  9|10) PF_CENTOS_MAJOR="$VERSION_ID" ;;
  *) printf '%s\n' '停止：不支持此 CentOS 版本，请先评估迁移。' >&2; exit 1 ;;
esac
test "$(id -u)" -ne 0
sudo -v
free -h
df -h /
sudo ss -lntp

read -rp 'API 域名，例如 api.your-domain.cn：' PF_DOMAIN
read -rp '证书通知邮箱：' PF_EMAIL
read -rp '你当前的公网出口 IPv4，不是电脑局域网 IP：' PF_TEAM_IP
[[ "$PF_DOMAIN" =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ ]]
[[ "$PF_TEAM_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
test -n "$PF_EMAIL"
~~~

每个阶段有命令失败就停下来排查，不要跳过错误继续执行。set -e 会让部分失败直接结束当前 shell，重新进入后按已完成进度继续。

## 1. 安装依赖、准备目录

如果服务器已有 Nginx、网站或证书，先审查配置及端口占用；本文不删除默认站点或覆盖别人的站点。

~~~bash
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --set-enabled crb
case "$PF_CENTOS_MAJOR" in
  9)
    sudo dnf install -y epel-release epel-next-release
    sudo dnf install -y python3.12 python3.12-pip
    ;;
  10)
    sudo dnf install -y epel-release
    sudo dnf install -y python3 python3-pip
    ;;
esac
sudo dnf makecache
sudo dnf install -y java-21-openjdk-devel maven git curl openssl \
  nginx certbot policycoreutils policycoreutils-python-utils \
  libselinux-utils audit firewalld tar gzip

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
test -x "$JAVA_HOME/bin/java"
"$JAVA_HOME/bin/java" -version
python3.12 --version
mvn -version

getent passwd pixelflow >/dev/null || \
  sudo useradd --system --user-group --home-dir /var/lib/pixelflow \
    --shell /usr/sbin/nologin pixelflow

sudo test ! -e /opt/pixelflow
sudo test ! -e /etc/pixelflow
sudo test ! -e /var/lib/pixelflow
sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 755 /opt/pixelflow
install -d -m 755 /opt/pixelflow/releases
sudo install -d -o root -g root -m 700 /etc/pixelflow
sudo install -d -o root -g root -m 755 /var/lib/pixelflow
sudo install -d -o pixelflow -g pixelflow -m 700 \
  /var/lib/pixelflow/java /var/lib/pixelflow/python
~~~

本文使用 JDK 21 运行 Java 17 目标代码、Python 3.12 创建应用虚拟环境。确认 mvn -version 也使用 JDK 21；不修改系统 Python 链接，不使用 sudo pip 安装应用依赖。若镜像仓库缺包、CRB/EPEL 不可用或签名校验失败，先检查仓库配置，不跳过 GPG/TLS 校验。EPEL 是 Fedora 社区扩展仓库，不代表所有包都由 CentOS 主仓维护。[Python 版本说明](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html/installing_and_using_dynamic_programming_languages/assembly_installing-and-using-python_installing-and-using-dynamic-programming-languages)

应用进程使用 pixelflow 账号；程序版本由部署账号维护。数据库、真实密钥不放入 Git。

### 1.1 firewalld：仅在已经运行时追加规则

下面不会启用原本关闭的防火墙，也不会改 SSH 规则。若它未运行，先确认是否使用其他防火墙；阿里云安全组仍必须配置。不要在不明确 SSH 放行条件时直接 systemctl enable --now firewalld。

~~~bash
if sudo systemctl is-active --quiet firewalld; then
  sudo firewall-cmd --get-active-zones
  read -rp '输入公网网卡当前所属的 zone（按上面实际输出填写）：' PF_FW_ZONE
  test -n "$PF_FW_ZONE"
  sudo firewall-cmd --zone="$PF_FW_ZONE" --list-all
  PF_HTTPS_RULE="rule family=\"ipv4\" source address=\"$PF_TEAM_IP/32\" port port=\"443\" protocol=\"tcp\" accept"
  sudo firewall-cmd --zone="$PF_FW_ZONE" --add-service=http
  sudo firewall-cmd --permanent --zone="$PF_FW_ZONE" --add-service=http
  sudo firewall-cmd --zone="$PF_FW_ZONE" --add-rich-rule="$PF_HTTPS_RULE"
  sudo firewall-cmd --permanent --zone="$PF_FW_ZONE" --add-rich-rule="$PF_HTTPS_RULE"
  sudo firewall-cmd --zone="$PF_FW_ZONE" --list-all
else
  printf '%s\n' 'firewalld 未运行：未修改其状态，请核对安全组及现有主机防火墙。'
fi
~~~

以上只增加规则，不会收紧已有的全网 HTTPS 放行、trusted zone 或其他宽泛规则；必须审查现状。选择实际公网网卡所在 zone，不默认假设 public；给队友开放时同步添加对应 rich rule、阿里云安全组和 Nginx 白名单。不执行 reload，以免把无关的运行时规则替换掉。

## 2. 获取代码、构建独立版本

公共仓库可直接克隆。若将来设为私有，使用受控的只读部署凭据，不把 token 拼进 URL，也不要关闭 Git TLS 校验。

~~~bash
git clone https://gitee.com/yyhSEU/pixel-flow.git /opt/pixelflow/source
cd /opt/pixelflow/source
git log -1 --oneline

PF_COMMIT="$(git rev-parse HEAD)"
PF_RELEASE="/opt/pixelflow/releases/$PF_COMMIT"
test ! -e "$PF_RELEASE"
install -d -m 755 "$PF_RELEASE"
git archive "$PF_COMMIT" | tar -x -C "$PF_RELEASE"
printf '%s\n' "$PF_COMMIT" > "$PF_RELEASE/RELEASE_COMMIT"

cd "$PF_RELEASE/server"
mvn -B package

cd "$PF_RELEASE/intelligence"
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements-lock.txt
.venv/bin/python -m pip install . --no-deps
.venv/bin/python -m pytest -q

test -f "$PF_RELEASE/server/target/pixelflow-server-0.1.0.jar"
test ! -e /opt/pixelflow/current
ln -s "$PF_RELEASE" /opt/pixelflow/current
~~~

锁定依赖下载失败、版本不兼容或测试失败时不要启动该版本；保留错误信息，按实际失败修复，不自动换成不受控的最新依赖。

## 3. 创建配置和随机密钥：只做一次

使用独立 H2 数据库用户、固定 JWT 密钥、两个不同的内部服务密钥。下面不会将密钥打印到终端，也不会把真实值写入命令历史。不要启用 shell 的 set -x，不要查看、截图或上传生成的 env 文件。

~~~bash
sudo test ! -e /etc/pixelflow/java.env
sudo test ! -e /etc/pixelflow/python.env
sudo test ! -e /etc/pixelflow/maintenance.env

PF_DB_PASSWORD="$(openssl rand -hex 32)"
PF_JWT_SECRET="$(openssl rand -hex 32)"
PF_EXPORT_TOKEN="$(openssl rand -hex 32)"
PF_MAINT_TOKEN="$(openssl rand -hex 32)"

{
  printf '%s\n' 'SERVER_ADDRESS=127.0.0.1' 'SERVER_PORT=8080'
  printf '%s\n' 'DB_URL=jdbc:h2:file:/var/lib/pixelflow/java/pixelflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
  printf '%s\n' 'DB_USER=pixelflow'
  printf 'DB_PASSWORD=%s\n' "$PF_DB_PASSWORD"
  printf 'PIXELFLOW_JWT_SECRET=%s\n' "$PF_JWT_SECRET"
  printf 'PIXELFLOW_ANALYTICS_EXPORT_TOKEN=%s\n' "$PF_EXPORT_TOKEN"
  printf 'PIXELFLOW_PUBLIC_BASE_URL=https://%s\n' "$PF_DOMAIN"
  printf '%s\n' 'PIXELFLOW_INTELLIGENCE_BASE_URL=http://127.0.0.1:8001'
  printf '%s\n' 'PIXELFLOW_SEED=true'
} | sudo tee /etc/pixelflow/java.env >/dev/null

{
  printf '%s\n' 'JAVA_BASE_URL=http://127.0.0.1:8080'
  printf '%s\n' 'ANALYTICS_DB=/var/lib/pixelflow/python/analytics.sqlite3'
  printf 'PIXELFLOW_ANALYTICS_EXPORT_TOKEN=%s\n' "$PF_EXPORT_TOKEN"
  printf 'PIXELFLOW_INTELLIGENCE_TOKEN=%s\n' "$PF_MAINT_TOKEN"
} | sudo tee /etc/pixelflow/python.env >/dev/null

{
  printf '%s\n' 'PIXELFLOW_MAINTENANCE_URL=http://127.0.0.1:8001'
  printf 'PIXELFLOW_INTELLIGENCE_TOKEN=%s\n' "$PF_MAINT_TOKEN"
} | sudo tee /etc/pixelflow/maintenance.env >/dev/null

sudo chmod 600 /etc/pixelflow/java.env /etc/pixelflow/python.env \
  /etc/pixelflow/maintenance.env
unset PF_DB_PASSWORD PF_JWT_SECRET PF_EXPORT_TOKEN PF_MAINT_TOKEN
~~~

这里的 env 由 systemd 读取，不要直接 source java.env，JDBC URL 中分号不是 shell 语法。已有数据库不能随意重生成 DB_PASSWORD；固定 JWT 密钥和内部密钥也不应在每次发布时更换。

此配置不创建管理员账号。演示账号没有共享固定登录密码，联调成员自行注册；管理员接入另行设计，不将管理员密钥放进公开命令。

## 4. 注册 Java、Python 服务

~~~bash
sudo test ! -e /etc/systemd/system/pixelflow-java.service
sudo test ! -e /etc/systemd/system/pixelflow-python.service

sudo tee /etc/systemd/system/pixelflow-java.service >/dev/null <<'UNIT'
[Unit]
Description=PixelFlow Java API
After=network.target

[Service]
Type=simple
User=pixelflow
Group=pixelflow
WorkingDirectory=/opt/pixelflow/current/server
EnvironmentFile=/etc/pixelflow/java.env
ExecStart=/usr/lib/jvm/java-21-openjdk/bin/java -Xms128m -Xmx512m -jar /opt/pixelflow/current/server/target/pixelflow-server-0.1.0.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=60
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/pixelflow/java

[Install]
WantedBy=multi-user.target
UNIT

sudo tee /etc/systemd/system/pixelflow-python.service >/dev/null <<'UNIT'
[Unit]
Description=PixelFlow Python analytics
After=network.target pixelflow-java.service

[Service]
Type=simple
User=pixelflow
Group=pixelflow
WorkingDirectory=/opt/pixelflow/current/intelligence
EnvironmentFile=/etc/pixelflow/python.env
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/opt/pixelflow/current/intelligence/.venv/bin/python -m uvicorn pixelflow_intelligence.api.app:create_app --factory --host 127.0.0.1 --port 8001 --workers 1
Restart=on-failure
RestartSec=5
TimeoutStopSec=60
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/pixelflow/python

[Install]
WantedBy=multi-user.target
UNIT

sudo systemd-analyze verify /etc/systemd/system/pixelflow-java.service \
  /etc/systemd/system/pixelflow-python.service
sudo systemctl daemon-reload
sudo systemctl enable --now pixelflow-java.service pixelflow-python.service

curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8080/health
curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8001/health
curl --fail 'http://127.0.0.1:8080/feed/?count=2&accept_video_clip=true'
sudo ss -lntp
~~~

确认 8080、8001 只监听 127.0.0.1。两个 health 只是存活检查，不代表画像链路已通。Java 堆上限 512 MiB 是初始配置，不是整个进程内存上限，需按服务器内存与负载观察调整。

种子内容生成且 Feed 正常后，关闭后续启动的自动种子开关：

~~~bash
sudo sed -i 's/^PIXELFLOW_SEED=true$/PIXELFLOW_SEED=false/' /etc/pixelflow/java.env
~~~

下次重启生效；已有内容不会删除。先检查 Feed 中媒体 URL 的域名正确，不能有 localhost、10.0.2.2 或旧电脑 IP。

## 5. 配置 HTTP 证书验证入口

初始 HTTP 站点只提供证书验证，不代理业务接口。确保阿里云安全组公网 80 可达；不要把团队 IP 限制套到证书验证路径上。

~~~bash
sudo test ! -e /etc/nginx/conf.d/pixelflow.conf
sudo install -d -m 755 /var/www/pixelflow-acme
sudo install -d -m 755 /etc/nginx/snippets

# 保持 SELinux 开启；允许 Nginx 连接回环地址上的 Java。
if selinuxenabled; then
  sudo setsebool -P httpd_can_network_connect on
  sudo semanage fcontext -a -t httpd_sys_content_t '/var/www/pixelflow-acme(/.*)?'
  sudo restorecon -Rv /var/www/pixelflow-acme
else
  printf '%s\n' '提示：当前 SELinux 已禁用；本指南未修改该状态。'
fi

sudo tee /etc/nginx/conf.d/pixelflow.conf >/dev/null <<NGINX
server {
    listen 80;
    server_name $PF_DOMAIN;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/pixelflow-acme;
    }
    location / { return 404; }
}
NGINX

sudo restorecon -Rv /etc/nginx
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx

sudo certbot certonly --webroot -w /var/www/pixelflow-acme \
  --cert-name "$PF_DOMAIN" -d "$PF_DOMAIN" \
  --email "$PF_EMAIL" --agree-tos --non-interactive
~~~

CentOS 的 Nginx 使用 /etc/nginx/conf.d/*.conf，不创建 sites-available/sites-enabled 链接。先确认发行版 nginx.conf 在 http 块中包含 conf.d/*.conf；若已有宝塔或自行编译的 Nginx，不应套用本文路径。setsebool 会允许 httpd 域网络连接，不仅限于当前应用，属于宿主机安全策略变更；多站点共享机器须先协调。若 semanage 提示规则已存在，先检查现有规则，不盲目覆盖。

签发失败先检查 DNS、80 端口、备案/接入限制及现有站点冲突；不要使用 curl -k 或关闭 Android 证书校验代替有效证书。

## 6. 配置 HTTPS、白名单和限流

~~~bash
printf 'allow %s/32;\ndeny all;\n' "$PF_TEAM_IP" | \
  sudo tee /etc/nginx/pixelflow-allowlist.conf >/dev/null

sudo tee /etc/nginx/conf.d/pixelflow-limits.conf >/dev/null <<'NGINX'
limit_req_zone $binary_remote_addr zone=pixelflow_auth:10m rate=2r/s;
limit_req_zone $binary_remote_addr zone=pixelflow_api:10m rate=30r/s;
NGINX

sudo tee /etc/nginx/snippets/pixelflow-proxy.conf >/dev/null <<'NGINX'
proxy_pass http://127.0.0.1:8080;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_connect_timeout 3s;
proxy_read_timeout 30s;
proxy_send_timeout 30s;
proxy_next_upstream off;
NGINX

sudo tee /etc/nginx/conf.d/pixelflow.conf >/dev/null <<NGINX
server {
    listen 80;
    server_name $PF_DOMAIN;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/pixelflow-acme;
    }
    location / { return 301 https://$PF_DOMAIN\$request_uri; }
}
server {
    listen 443 ssl;
    server_name $PF_DOMAIN;
    ssl_certificate /etc/letsencrypt/live/$PF_DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$PF_DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    include /etc/nginx/pixelflow-allowlist.conf;
    client_max_body_size 9m;
    limit_req_status 429;
    access_log /var/log/nginx/pixelflow-access.log;
    error_log /var/log/nginx/pixelflow-error.log;

    location = /api/internal { return 404; }
    location ^~ /api/internal/ { return 404; }
    location = /api/admin { return 404; }
    location ^~ /api/admin/ { return 404; }
    location ~ ^/api/auth/(register|login|refresh)$ {
        limit_req zone=pixelflow_auth burst=10 nodelay;
        include /etc/nginx/snippets/pixelflow-proxy.conf;
    }
    location / {
        limit_req zone=pixelflow_api burst=80 nodelay;
        include /etc/nginx/snippets/pixelflow-proxy.conf;
    }
}
NGINX

sudo restorecon -Rv /etc/nginx /etc/letsencrypt
sudo nginx -t
sudo systemctl reload nginx

sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudo tee /etc/letsencrypt/renewal-hooks/deploy/pixelflow-nginx.sh >/dev/null <<'SH'
#!/bin/sh
set -eu
/usr/sbin/nginx -t
/usr/bin/systemctl reload nginx
SH
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/pixelflow-nginx.sh
sudo restorecon -Rv /etc/letsencrypt
sudo systemctl enable --now certbot-renew.timer
sudo certbot renew --dry-run
~~~

EPEL 包通常提供 certbot-renew.timer，而不是 Ubuntu 的 certbot.timer。若找不到，先运行 rpm -ql certbot 与 systemctl list-unit-files 'certbot*' 核对实际安装来源，不静默跳过续期。

新增团队出口时用 sudoedit /etc/nginx/pixelflow-allowlist.conf，在 deny all 前增加 allow 公网IP/32;，然后 sudo nginx -t && sudo systemctl reload nginx；同时更新阿里云安全组和已启用的 firewalld。不要把 allow 写在 deny all 之后。上面的正则只校验 IPv4 外形，实际有效性仍须确认。

该示例不启用代理缓存；不要给受权限保护的媒体添加无条件缓存规则。限流以公网 IP 计，校园网共用出口可能影响三人的请求，出现 429 时结合日志调整，而不是直接无限放开。日志不应自定义记录 Authorization、密码或请求正文。

## 7. 设置 Python 同步和画像维护

~~~bash
sudo tee /etc/systemd/system/pixelflow-maintenance.service >/dev/null <<'UNIT'
[Unit]
Description=PixelFlow bounded analytics maintenance
Requires=pixelflow-java.service pixelflow-python.service
After=pixelflow-java.service pixelflow-python.service

[Service]
Type=oneshot
User=pixelflow
Group=pixelflow
WorkingDirectory=/opt/pixelflow/current/intelligence
EnvironmentFile=/etc/pixelflow/maintenance.env
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/opt/pixelflow/current/intelligence/.venv/bin/python -m pixelflow_intelligence.maintenance --budget-seconds 120
TimeoutStartSec=180
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
UNIT

sudo tee /etc/systemd/system/pixelflow-maintenance.timer >/dev/null <<'UNIT'
[Unit]
Description=Periodically sync PixelFlow analytics

[Timer]
OnBootSec=2min
OnUnitInactiveSec=1min
Unit=pixelflow-maintenance.service

[Install]
WantedBy=timers.target
UNIT

sudo systemctl daemon-reload
sudo systemctl start pixelflow-maintenance.service
sudo journalctl -u pixelflow-maintenance.service -n 30 --no-pager
sudo systemctl enable --now pixelflow-maintenance.timer
sudo systemctl list-timers pixelflow-maintenance.timer
~~~

同一个 systemd oneshot 运行时不会由此 timer 再启动第二份。不要同时绕过 systemd 手工运行另一份维护命令。返回 complete 才代表本轮完成；退出码 2 是未追完，不是成功。若持续达到分页上限，检查日志并按 maintenance 文档增加页数/安排续跑；本 timer 不保存输出里的 refresh_after，不声称已实现完整的失败续跑编排。

## 8. Android 与端到端验收

在开发电脑将 app/src/main/assets/api.properties 改成自己的 HTTPS origin：

~~~properties
url=https://api.your-domain.cn
~~~

这里没有路径后缀。重新构建并安装 Android APK；服务端密钥绝不放进 APK。手机和模拟器的公网出口必须在白名单中；手机走流量时通常与电脑不是同一个出口。正式发布还应收紧 Android 明文网络配置，本指南不是应用商店发布流程。

从允许访问的开发电脑检查（PowerShell 可使用 curl.exe）：

~~~bash
curl --fail https://api.your-domain.cn/health
curl --fail 'https://api.your-domain.cn/feed/?count=2&accept_video_clip=true'
curl -s -o /dev/null -w '%{http_code}\n' \
  https://api.your-domain.cn/api/internal/analytics/events
~~~

最后一个应为 404，而不是返回内部数据。再验证：

- 每人注册独立测试账号；登录、Feed 翻页、图片/视频访问、点赞和取消点赞正常。
- 两个账号的数据不混用；退出后不能继续写入。
- 点赞/浏览后等待维护，查看账号页兴趣画像；health 成功不能代替此项。
- 从非白名单网络无法访问业务；公网不能直连 8080、8001。
- 重启 Java 后账号和作品不丢失。更换部署版本时不重置数据库。

## 9. 日常查看和重启

~~~bash
sudo systemctl status pixelflow-java pixelflow-python --no-pager
sudo journalctl -u pixelflow-java -n 100 --no-pager
sudo journalctl -u pixelflow-python -n 100 --no-pager
sudo journalctl -u pixelflow-maintenance -n 50 --no-pager
sudo systemctl list-timers pixelflow-maintenance.timer certbot-renew.timer
cat /opt/pixelflow/current/RELEASE_COMMIT

# 仅重启需要的服务；不是重新安装，也不删除数据。
sudo systemctl restart pixelflow-java
curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8080/health
~~~

服务失败不要不断手工启动新的 Java/Python 进程。先查日志、端口、磁盘、权限和内存，确保只有一套服务占用数据库。

CentOS 特有排查：出现 Nginx 502、证书/验证文件不可读、服务 EXEC 权限错误时，核对 SELinux 及文件权限，不执行 setenforce 0、关闭 SELinux 或 chmod -R 777：

~~~bash
getenforce
sudo getsebool httpd_can_network_connect
sudo ls -Zd /opt/pixelflow /var/www/pixelflow-acme /etc/nginx /etc/letsencrypt
sudo journalctl -u nginx -n 80 --no-pager
sudo ausearch -m AVC,USER_AVC -ts recent || true
~~~

如果系统没有 audit 工具/审计服务，上面的 ausearch 不能提供完整证据。先依据 AVC 中的对象路径与拒绝类型修正标记或策略，不能把任意目录标记为网页内容，也不要直接把 audit2allow 输出全部放行。

## 10. 备份：发布前执行，也应定期执行

这是会短暂停服的冷备份，备份 H2、SQLite、私有配置和版本位置。只在这套服务已经正常运行后使用。这里没有自动备份定时任务；建议后续补充异地备份和恢复演练。

~~~bash
sudo bash <<'SH'
set -euo pipefail
install -d -o root -g root -m 755 /var/backups
backup_dir="$(mktemp -d /var/backups/pixelflow-XXXXXXXX)"
chmod 700 "$backup_dir"
trap 'systemctl start pixelflow-java.service pixelflow-python.service; systemctl start pixelflow-maintenance.timer' EXIT
systemctl stop pixelflow-maintenance.timer
systemctl stop pixelflow-maintenance.service
systemctl stop pixelflow-python.service pixelflow-java.service
tar -C /var/lib/pixelflow -czf "$backup_dir/data.tar.gz" java python
cp -a /etc/pixelflow "$backup_dir/config"
readlink -f /opt/pixelflow/current > "$backup_dir/release.txt"
sha256sum "$backup_dir/data.tar.gz" > "$backup_dir/data.sha256"
printf 'Backup saved: %s\n' "$backup_dir"
SH

curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8080/health
curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8001/health
~~~

若备份命令失败，不要部署新版本。备份包含密码、令牌相关数据及私有配置，应通过受控加密通道复制到异机或私有备份存储，不能上传 Git 或公开网盘。同盘备份不能抵御整台服务器丢失。

恢复时先停止维护和两个服务，在独立目录校验归档并恢复到独立数据路径，与对应配置、程序版本一起验证。不要直接覆盖当前业务库。出现故障时应保留故障后的数据副本，再决定是否恢复，否则会丢失备份之后的新数据。

## 11. 后续更新：先构建，再短暂停服切换

开发同学本地开发、测试、提交合并请求；服务器只部署已确认的提交，不在运行版本目录改源码。固定一个部署负责人，禁止多人同时执行以下步骤。

### 11.1 构建新版本，不影响正在运行的版本

~~~bash
set -euo pipefail
umask 022
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
test -x "$JAVA_HOME/bin/java"
cd /opt/pixelflow/source
test -z "$(git status --porcelain)"
git fetch origin
read -rp '输入已审核通过的完整 Git 提交 SHA：' PF_COMMIT
[[ "$PF_COMMIT" =~ ^[0-9a-f]{40}$ ]]
git cat-file -e "$PF_COMMIT^{commit}"
git merge-base --is-ancestor "$PF_COMMIT" origin/master

PF_RELEASE="/opt/pixelflow/releases/$PF_COMMIT"
test ! -e "$PF_RELEASE"
install -d -m 755 "$PF_RELEASE"
git archive "$PF_COMMIT" | tar -x -C "$PF_RELEASE"
printf '%s\n' "$PF_COMMIT" > "$PF_RELEASE/RELEASE_COMMIT"

cd "$PF_RELEASE/server"
mvn -B package
cd "$PF_RELEASE/intelligence"
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements-lock.txt
.venv/bin/python -m pip install . --no-deps
.venv/bin/python -m pytest -q
~~~

若该 SHA 的目录已存在，先判断它是成功构建还是失败残留，不盲目覆盖、删除或当作成功版本使用。版本目录内的虚拟环境不要创建后再搬家。

### 11.2 先完成第 10 节备份，再切换

确认本次数据库迁移及回退兼容性；Java 有新 Flyway 脚本时，首次启动会执行。切换期间有短暂停机。

~~~bash
test -n "$PF_RELEASE"
test -f "$PF_RELEASE/RELEASE_COMMIT"
test -f "$PF_RELEASE/server/target/pixelflow-server-0.1.0.jar"
test -x "$PF_RELEASE/intelligence/.venv/bin/python"
test -L /opt/pixelflow/current
test ! -e /opt/pixelflow/current.next
PF_PREVIOUS="$(readlink -f /opt/pixelflow/current)"
printf 'Old: %s\nNew: %s\n' "$PF_PREVIOUS" "$PF_RELEASE"

sudo systemctl stop pixelflow-maintenance.timer
sudo systemctl stop pixelflow-maintenance.service
sudo systemctl stop pixelflow-python.service pixelflow-java.service
ln -s "$PF_RELEASE" /opt/pixelflow/current.next
mv -Tf /opt/pixelflow/current.next /opt/pixelflow/current
sudo systemctl start pixelflow-java.service pixelflow-python.service
curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8080/health
curl --fail --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8001/health
sudo systemctl start pixelflow-maintenance.service
sudo systemctl start pixelflow-maintenance.timer
~~~

再从 Android 验证登录、Feed、媒体和画像，告知队友部署的 SHA。构建失败不切换；切换后健康检查失败时脚本会停下，维护 timer 保持关闭，查日志处理后再恢复，不在失败状态下继续联调。

回退不能只机械修改 current：若新版本已执行不兼容数据库迁移，需要制定对应的数据恢复方案。只有确认旧程序与当前数据库兼容时，才可停服后将 current 切回已记录的 PF_PREVIOUS，再做同样的健康与业务验证。不要删库“解决”迁移失败。

## 12. 协作约定和仍待完善的部分

- Android 本机运行，访问云端 HTTPS；Java/Python 同学仍使用本地独立数据库开发。
- 当前客户端地址仍是一个配置文件，尚未实现 local/staging 构建变体。不要把个人局域网地址覆盖到团队配置，也不要把内部密钥写进客户端。
- 共享联调库不是单元测试库，禁止用会清空数据的测试脚本连接它。
- 当前采用单 Java 实例、单 Python worker；不把 H2 文件交给多个 Java 实例同时使用。
- 尚未在此服务器实测容量、安全性或兼容性；监控告警、日志保留、异地自动备份、完整恢复演练、发布自动化、公开注册防滥用等仍需后续补齐。
- 不部署 Web，不需要 Redis、MySQL、模型密钥，也不在本轮完成 Agent/MCP。

## 参考

- [阿里云安全组](https://www.alibabacloud.com/help/zh/ecs/user-guide/start-using-security-groups)
- [阿里云 App 备案](https://help.aliyun.com/zh/icp-filing/basic-icp-service/support/basics-about-icp-filling-for-apps)
- [CentOS Stream 9](https://www.centos.org/stream9/)
- [CentOS Stream 10](https://www.centos.org/centos10/)
- [EPEL 入门与仓库配置](https://docs.fedoraproject.org/en-US/epel/getting-started/)
- [EPEL 9 Certbot 包与定时器](https://packages.fedoraproject.org/pkgs/certbot/certbot/epel-9.html)
- [EPEL 10 Certbot 包与定时器](https://packages.fedoraproject.org/pkgs/certbot/certbot/epel-10.2.html)
- [RHEL Nginx 与 SELinux 反向代理配置](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html-single/deploying_web_servers_and_reverse_proxies/index)
- [firewalld rich rules](https://firewalld.org/documentation/man-pages/firewalld.richlanguage.html)
- [Nginx 反向代理](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- [Nginx 请求大小限制](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size)
- [systemd 服务环境](https://www.freedesktop.org/software/systemd/man/257/systemd.exec.html)
- [systemd 定时器](https://www.freedesktop.org/software/systemd/man/257/systemd.timer.html)
