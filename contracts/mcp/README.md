# MCP 创作者工具契约 v0.1

PixelFlow 通过一个 MCP Server 把"创作者"能力暴露给外部 Agent（Claude Desktop、Cherry Studio、Cursor、
Trae 等）。规划、多轮对话与追问由客户端 Agent 承担；服务端只提供**受控、可审计**的工具，不内置 Agent。

- 传输：`stdio`（桌面客户端直接拉起）与 `streamable-http`（远程/多客户端，路径 `/mcp`）
- 服务实现：`intelligence/src/pixelflow_intelligence/mcp/`
- 身份：独立服务账号（Java `users` 表中的真实账号，CREATOR 角色），**不复用任何人的登录态**
- 业务事实：仍以 Java 服务为准；MCP 层不直接读写数据库

## 鉴权与权限

| 机制 | 说明 |
| --- | --- |
| 登录 | 服务账号用 `POST /api/auth/login` 换取 access token，缓存 8 分钟，401 时自动重登一次 |
| 凭据来源 | 仅环境变量 `PIXELFLOW_MCP_SERVICE_USERNAME` / `PIXELFLOW_MCP_SERVICE_PASSWORD`，不写配置文件、不进 Git |
| Scope | `media:write`、`post:write`、`post:read`，由 `PIXELFLOW_MCP_SCOPES` 控制（逗号分隔） |
| 越权 | 缺 scope 时工具在转发请求**之前**拒绝，审计记为 `denied` |
| 未开放能力 | 删除作品、修改他人作品、批量操作、审核他人内容一律不封装 |

## 工具

| 工具 | Scope | 入参 | 返回 | 写库 |
| --- | --- | --- | --- | --- |
| `upload_media` | `media:write` | `file_path`：本地绝对路径，PNG/JPEG，≤8MB | `media_id, width, height, size, content_type` | 是（媒体表） |
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

## 本地启动

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

# 4) 启动（stdio 默认；远程用 --transport streamable-http --port 8765）
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

- `upload_media` 只接受**运行 MCP Server 这台机器上的本地路径**（stdio 模式天然满足）；远程 HTTP 模式需要先把文件放到服务端。
- `list_my_posts` 在最近 150 条自己的作品窗口内过滤（3 页 × 50），未把 `status` 下推到 SQL；作品量增大后再加服务端筛选参数。
- 工具返回的文本内容为 JSON 字符串（MCP 客户端按 `content[0].text` 解析），未启用结构化输出 schema。
- 不提供 Agent 规划、多轮记忆或追问；这些由客户端负责，服务端只保证每次调用可控、可审计。
