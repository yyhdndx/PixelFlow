# MCP 创作者工具契约 v0.1

PixelFlow 通过一个 MCP Server 把"创作者"能力暴露给外部 Agent（Claude Desktop、Cherry Studio、Cursor、
Trae 等）。规划、多轮对话与追问由客户端 Agent 承担；服务端只提供**受控、可审计**的工具，不内置 Agent。

- 传输：**`streamable-http`（远程部署，默认推荐，路径 `/mcp`）** 与 `stdio`（桌面客户端直接拉起）
- 服务实现：`intelligence/src/pixelflow_intelligence/mcp/`
- 身份：独立服务账号（Java `users` 表中的真实账号，CREATOR 角色），**不复用任何人的登录态**
- 业务事实：仍以 Java 服务为准；MCP 层不直接读写数据库

## 鉴权与权限

| 机制 | 说明 |
| --- | --- |
| 客户端鉴权（远程） | 每个请求必须带 `Authorization: Bearer <PIXELFLOW_MCP_HTTP_TOKEN>`；缺失或错误一律 401，且在工具执行前拒绝 |
| 客户端鉴权（stdio） | 由操作系统进程边界保证，无需额外 token（谁都启动不了别人的进程） |
| 登录 | 服务账号用 `POST /api/auth/login` 换取 access token，缓存 8 分钟，401 时自动重登一次 |
| 凭据来源 | 仅环境变量 `PIXELFLOW_MCP_SERVICE_USERNAME` / `PIXELFLOW_MCP_SERVICE_PASSWORD` / `PIXELFLOW_MCP_HTTP_TOKEN`，不写配置文件、不进 Git |
| Scope | `media:write`、`post:write`、`post:read`，由 `PIXELFLOW_MCP_SCOPES` 控制（逗号分隔） |
| 越权 | 缺 scope 时工具在转发请求**之前**拒绝，审计记为 `denied` |
| 未开放能力 | 删除作品、修改他人作品、批量操作、审核他人内容一律不封装 |

> 远程模式**拒绝在没有 token 的情况下启动**（退出码 2），不会出现"裸奔"的 HTTP 端点。

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
# /etc/pixelflow/mcp.env（600，root:root）
PIXELFLOW_MCP_JAVA_URL=http://127.0.0.1:8080
PIXELFLOW_MCP_SERVICE_USERNAME=mcp_creator
PIXELFLOW_MCP_SERVICE_PASSWORD=<服务账号密码>
PIXELFLOW_MCP_HTTP_TOKEN=<至少 32 位随机 ASCII，例如 openssl rand -hex 32>
PIXELFLOW_MCP_PUBLIC_URL=https://pixelflow.example.com
PIXELFLOW_MCP_SCOPES=media:write,post:write,post:read
PIXELFLOW_MCP_AUDIT_LOG=/var/log/pixelflow/mcp-audit.jsonl
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
location /mcp {
    # 客户端必须自带 Bearer；Nginx 只做 TLS 终止与体积限制
    client_max_body_size 12m;
    proxy_pass http://127.0.0.1:8765/mcp;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 120s;
    proxy_buffering off;          # Streamable HTTP 的流式响应
}
```

客户端只需填 URL 与 token（示例为支持远程 MCP 的客户端配置）：

```json
{
  "mcpServers": {
    "pixelflow-creator": {
      "url": "https://pixelflow.example.com/mcp",
      "headers": { "Authorization": "Bearer <PIXELFLOW_MCP_HTTP_TOKEN>" }
    }
  }
}
```

**轮换**：改 `PIXELFLOW_MCP_HTTP_TOKEN` → `systemctl restart pixelflow-mcp` → 同步更新客户端；旧 token 立即失效。
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
- 远程模式目前是**单个共享 Bearer token**，不区分调用方身份；多客户端/需要逐用户审计时应升级为 OAuth 2.1（MCP 规范推荐路径）。
- `list_my_posts` 在最近 150 条自己的作品窗口内过滤（3 页 × 50），未把 `status` 下推到 SQL；作品量增大后再加服务端筛选参数。
- 工具返回的文本内容为 JSON 字符串（MCP 客户端按 `content[0].text` 解析），未启用结构化输出 schema。
- 不提供 Agent 规划、多轮记忆或追问；这些由客户端负责，服务端只保证每次调用可控、可审计。
