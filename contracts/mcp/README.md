# MCP 创作者工具契约 v0.1

PixelFlow 通过一个 MCP Server 把"创作者"能力暴露给外部 Agent（Claude Desktop、Cherry Studio、Cursor、
Trae 等）。规划、多轮对话与追问由客户端 Agent 承担；服务端只提供**受控、可审计**的工具，不内置 Agent。

- 传输：**`streamable-http`（远程部署，默认推荐，路径 `/mcp`）** 与 `stdio`（桌面客户端直接拉起）
- 服务实现：`intelligence/src/pixelflow_intelligence/mcp/`
- 身份：**远程默认 OAuth 2.1，每个客户端代表浏览器里登录的那个用户**；stdio 与静态 token 模式使用独立服务账号
- 业务事实：仍以 Java 服务为准；MCP 层不直接读写数据库

## 鉴权与权限

三种模式，优先级从高到低：

| 模式 | 客户端如何证明身份 | 业务身份 | 适用 |
| --- | --- | --- | --- |
| **OAuth 2.1（远程默认）** | 浏览器登录后拿到的 MCP access token | **登录用户本人** | 服务器部署、真实演示 |
| 静态 Bearer（设置 `PIXELFLOW_MCP_HTTP_TOKEN`） | 预共享字符串 | 服务账号 `mcp_creator` | 服务对服务、临时排障 |
| stdio | 操作系统进程边界 | 服务账号 `mcp_creator` | 本机开发 |

| 机制 | 说明 |
| --- | --- |
| 授权端点 | `/.well-known/oauth-authorization-server`、`/authorize`、`/token`、`/register`、`/revoke`、`/oauth/login` |
| 客户端注册 | 动态注册（RFC 7591）开启，`valid_scopes` 为三个业务 scope，默认 `post:read` |
| PKCE | 强制授权码 + S256；不存 client secret（公共客户端） |
| 令牌 | MCP access token 1 小时、refresh 30 天；Java token 由 MCP 在到期前 60 秒自动用 refresh 轮换 |
| Scope | `media:write`、`post:write`、`post:read`，由 OAuth 授权时授予，工具执行前逐个校验 |
| 越权 | 缺 scope 时工具在转发请求**之前**拒绝，审计记为 `denied` |
| 凭据 | 用户密码只在登录表单提交一次、直接转发给 Java `/api/auth/login`，**不落盘、不写日志**；MCP 只保留 Java 令牌 |
| 撤销 | `POST /revoke` 撤销 access/refresh；进程重启后所有令牌失效（内存态） |
| 未开放能力 | 删除作品、修改他人作品、批量操作、审核他人内容一律不封装 |

> 静态 token 模式**拒绝在缺少 token 时启动**（退出码 2）；OAuth 模式则必须能对外提供 `PIXELFLOW_MCP_PUBLIC_URL`。

### OAuth 登录流程

```
客户端 ──GET /authorize（code_challenge, state）──▶ MCP
MCP    ──302 /oauth/login?request_id=...─────────▶ 浏览器
浏览器 ──用户名/密码──▶ MCP ──POST /api/auth/login──▶ Java
MCP    ──302 redirect_uri?code=...&state=...─────▶ 客户端
客户端 ──POST /token（code + code_verifier）─────▶ MCP ──▶ access/refresh token
客户端 ──Authorization: Bearer <access>─────────▶ /mcp
```

- 待登录请求 10 分钟有效，密码错误**不会**消耗该请求，用户可原地重试；
- 登录页只包含用户名/密码，不展示第三方信息；错误以固定文案返回（401）；
- 授权码 5 分钟、一次性；PKCE 校验由 SDK 完成。

## 工具

| 工具 | Scope | 入参 | 返回 | 写库 |
| --- | --- | --- | --- | --- |
| `upload_media` | `media:write` | `file_path`（本地绝对路径）**或** `content_base64` + `filename`（远程），PNG/JPEG，≤8MB | `media_id, width, height, size, content_type` | 是（媒体表） |
| `create_post_draft` | `post:write` | `title`(≤120)、`content`(≤5000)、`media_ids`(≤9)、`idempotency_key`(UUID，可选) | `post_id, status=DRAFT, title, media_ids` | 是（作品表） |
| `update_post_draft` | `post:write` | `post_id`、`title`、`content`、`media_ids` | `post_id, status, title, media_ids` | 是 |
| `submit_post_for_review` | `post:write` | `post_id` | `post_id, status=PENDING` | 是（状态机） |
| `withdraw_post` | `post:write` | `post_id` | `post_id, status=WITHDRAWN` | 是 |
| `list_my_posts` | `post:read` | `status`（DRAFT/PENDING/PUBLISHED/REJECTED/WITHDRAWN）、`query`（标题/正文关键词，≤60 字）、`limit`(1–20) | `total_scanned, items[{post_id,title,status,create_time,media_count}], has_more` | 否 |

### 状态机与人工审核

`DRAFT → PENDING`（`submit_post_for_review`）→ 管理员审核 → `PUBLISHED` 或 `REJECTED`。
MCP **只能把作品推到 PENDING**，不能直接发布、不能审核自己的作品；`withdraw_post` 可把作品置为
`WITHDRAWN`，之后可用 `update_post_draft` 继续修改并重新提交。

### 错误码

| code | 含义 |
| --- | --- |
| `INVALID_ARGUMENT` | 参数类型、长度、格式或取值范围不合法 |
| `FORBIDDEN` | 服务账号缺少所需 scope |
| `UNAUTHORIZED` | 服务账号会话失效且重登失败 |
| `NOT_FOUND` | 作品不存在或不属于该账号 |
| `CONFLICT` | 当前作品状态不允许该操作（例如已发布作品直接改稿） |
| `MEDIA_TOO_LARGE` | 图片超过 8MB |
| `UPSTREAM_TIMEOUT` / `UPSTREAM_UNAVAILABLE` | Java 服务超时或不可用 |
| `INVALID_UPSTREAM_RESPONSE` | 上游返回无法解析的响应 |

错误信息为固定文案，不回显上游响应正文、令牌或数据库细节。

## 审计

每次调用写一行 JSONL（`PIXELFLOW_MCP_AUDIT_LOG`，未配置时写 stderr）：

```json
{"ts":1793285193.1,"event":"mcp_tool","tool":"submit_post_for_review","account":"mcp_creator",
 "status":"ok","params":{"post_id":"34"},"duration_ms":24.3}
```

- 记录：工具名、服务账号、状态（`ok`/`denied`/`error`）、耗时、`post_id`、`limit`；
- 不记录：标题正文原文、令牌、密码、文件内容；`query` 与 `user_id` 只存 sha256 前 12 位；
- 失败时附 `error`（错误码），可直接用于答辩演示"Agent 的每一步都有痕迹"。

## 远程部署（推荐）

后端在服务器上时，MCP Server 也跑在服务器上，只监听回环，由 Nginx 终止 TLS 并转发：

```
Agent 客户端 ──HTTPS + Bearer──> Nginx(/mcp) ──HTTP 127.0.0.1:8765──> MCP Server ──> Java 127.0.0.1:8080
```

```bash
# /etc/pixelflow/mcp.env（600，root:root）—— OAuth 模式（推荐）
PIXELFLOW_MCP_JAVA_URL=http://127.0.0.1:8080
PIXELFLOW_MCP_PUBLIC_URL=https://pixelflow.example.com
PIXELFLOW_MCP_AUDIT_LOG=/var/log/pixelflow/mcp-audit.jsonl

# 可选：设置下面两项即退回“服务账号 + 静态 Bearer”，不再启用 OAuth
# PIXELFLOW_MCP_SERVICE_USERNAME=mcp_creator
# PIXELFLOW_MCP_SERVICE_PASSWORD=<服务账号密码>
# PIXELFLOW_MCP_HTTP_TOKEN=<至少 32 位随机 ASCII>
```

```ini
# systemd: /etc/systemd/system/pixelflow-mcp.service
[Unit]
Description=PixelFlow MCP creator server
After=network.target pixelflow-java.service

[Service]
User=pixelflow
WorkingDirectory=/opt/pixelflow/current/intelligence
EnvironmentFile=/etc/pixelflow/mcp.env
ExecStart=/opt/pixelflow/current/intelligence/.venv/bin/python -m pixelflow_intelligence.mcp \
    --transport streamable-http --host 127.0.0.1 --port 8765
Restart=on-failure
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```nginx
# OAuth 端点也要暴露，否则客户端无法完成授权
location ~ ^/(mcp|authorize|token|register|revoke|oauth|\.well-known) {
    client_max_body_size 12m;
    proxy_pass http://127.0.0.1:8765;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 120s;
    proxy_buffering off;          # Streamable HTTP 的流式响应
}
```

客户端只需要填 URL，首次连接会弹出浏览器登录：

```json
{
  "mcpServers": {
    "pixelflow-creator": { "url": "https://pixelflow.example.com/mcp" }
  }
}
```

使用静态 token 的回退模式时，才需要额外带 header：

```json
{ "url": "https://pixelflow.example.com/mcp",
  "headers": { "Authorization": "Bearer <PIXELFLOW_MCP_HTTP_TOKEN>" } }
```

**登出/轮换**：OAuth 模式调用 `POST /revoke` 撤销令牌，或重启进程让全部内存令牌失效；静态模式改
`PIXELFLOW_MCP_HTTP_TOKEN` 后重启并同步客户端。
**反向代理注意**：`/mcp` 必须允许流式响应（关闭 `proxy_buffering`），且不要缓存。

## 本地启动（stdio）

```powershell
# 1) 依赖（可选组，装上 MCP SDK；不影响分析服务现有依赖版本）
cd intelligence
uv pip install -e ".[mcp]"          # 或按 requirements-lock.txt 的 MCP 段落安装

# 2) 服务账号（首次）
#    用 Java 注册接口创建一次，例如 mcp_creator / <强密码>

# 3) 环境变量
$env:PIXELFLOW_MCP_JAVA_URL='http://127.0.0.1:8080'
$env:PIXELFLOW_MCP_SERVICE_USERNAME='mcp_creator'
$env:PIXELFLOW_MCP_SERVICE_PASSWORD='<密码>'
$env:PIXELFLOW_MCP_SCOPES='media:write,post:write,post:read'
$env:PIXELFLOW_MCP_AUDIT_LOG='server/target/mcp-audit.jsonl'

# 4) 启动（stdio 默认；远程见上一节）
.\.venv\Scripts\python.exe -m pixelflow_intelligence.mcp
```

桌面客户端（以 Claude Desktop 为例）：

```json
{
  "mcpServers": {
    "pixelflow-creator": {
      "command": "G:\\project\\2026\\pixel-flow\\intelligence\\.venv\\Scripts\\python.exe",
      "args": ["-m", "pixelflow_intelligence.mcp"],
      "env": {
        "PYTHONPATH": "G:\\project\\2026\\pixel-flow\\intelligence\\src",
        "PIXELFLOW_MCP_JAVA_URL": "http://127.0.0.1:8080",
        "PIXELFLOW_MCP_SERVICE_USERNAME": "mcp_creator",
        "PIXELFLOW_MCP_SERVICE_PASSWORD": "<密码>",
        "PIXELFLOW_MCP_AUDIT_LOG": "G:\\project\\2026\\pixel-flow\\server\\target\\mcp-audit.jsonl"
      }
    }
  }
}
```

> 凭据放在客户端配置里属于本机开发用法；正式环境应由密钥管理或启动器注入，不提交到仓库。

## 限制

- 远程模式下 `upload_media` 用 `content_base64` + `filename`（8MB 图片 base64 后约 11MB，服务端已把请求体上限调到 12MB，Nginx 也需同步 `client_max_body_size`）；`file_path` 只对 stdio 或运维脚本有意义。
- OAuth 令牌、授权码、客户端注册均为**进程内存**：重启即全部失效，且只能单实例运行；多副本部署需要换成 Redis/数据库存储并共享签名密钥。
- 授权页只有登录表单，没有细粒度 scope 勾选（客户端注册时一次申请全部业务 scope）；撤销按令牌粒度，不提供"查看已授权应用"页面。
- 动态客户端注册未做人工审核，任何能访问 `/register` 的客户端都可注册；如需限制，应在 Nginx 层加白名单或关闭 DCR 改为预注册。
- `list_my_posts` 在最近 150 条自己的作品窗口内过滤（3 页 × 50），未把 `status` 下推到 SQL；作品量增大后再加服务端筛选参数。
- 工具返回的文本内容为 JSON 字符串（MCP 客户端按 `content[0].text` 解析），未启用结构化输出 schema。
- 不提供 Agent 规划、多轮记忆或追问；这些由客户端负责，服务端只保证每次调用可控、可审计。
