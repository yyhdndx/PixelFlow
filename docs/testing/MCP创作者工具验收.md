# MCP 创作者工具验收

对应"Agent-Skill-MCP 硬指标"中的 MCP 层。本轮只新增 MCP 服务与测试，**不修改 Java 代码、不改变分析服务
既有依赖版本**（`server/` 无改动；`pyproject.toml` 只新增可选组 `[mcp]`）。

## 交付内容

| 文件 | 作用 |
| --- | --- |
| `intelligence/src/pixelflow_intelligence/mcp/config.py` | 服务账号、scope、JAVA URL、审计路径配置 |
| `…/mcp/auth.py` | 服务账号登录、token 缓存（8 分钟）与失效重登 |
| `…/mcp/gateway.py` | 调 Java 的 6 个端点；错误码映射与脱敏 |
| `…/mcp/tools.py` | 6 个创作者工具及入参校验、scope 校验、审计 |
| `…/mcp/server.py` | 组装 MCP Server（stdio / streamable-http 共用） |
| `…/mcp/__main__.py` | `python -m pixelflow_intelligence.mcp` 入口 |
| `intelligence/tests/test_mcp_tools.py` | 8 个单元测试（无网络） |
| `intelligence/scripts/smoke_mcp_creator.py` | 对真实后端的端到端冒烟 |
| `contracts/mcp/README.md` | 工具契约、错误码、审计与客户端配置 |

## 工具与写库边界

| 工具 | 写库 | 说明 |
| --- | --- | --- |
| `upload_media` | 是 | 仅本地 PNG/JPEG，≤8MB，返回 `media_id` |
| `create_post_draft` | 是 | 支持 `Idempotency-Key`，重试不重复建稿 |
| `update_post_draft` | 是 | 仅 DRAFT / REJECTED / WITHDRAWN 可改 |
| `submit_post_for_review` | 是 | 只到 `PENDING`，**不能直接发布** |
| `withdraw_post` | 是 | 置为 `WITHDRAWN`，可再编辑重提 |
| `list_my_posts` | 否 | 最近 150 条内按状态/关键词过滤 |

未开放：删除作品、修改他人作品、审核他人内容、批量操作。

## 本地验证记录（2026-10-29）

环境：Windows、Java 17 目标（本机运行 Java 24）、独立 MySQL 8.4.2（端口 3307）、Python 3.13.13。

| 检查 | 结果 |
| --- | --- |
| 新增单元测试 `tests/test_mcp_tools.py` | **12 passed**（入参校验、scope 拒绝、审计脱敏、6 工具注册、base64 上传、Bearer 校验、远程模式强制 token） |
| Python 全量回归 | **211 passed, 6 skipped**（原 199 + 新增 12；跳过仍是未配置 MySQL 的分析套件） |
| Java 代码改动 | **无**（`git status` 中 `server/` 无变更，原测试基线不受影响） |
| 端到端（真实 Java + MySQL，stdio） | 上传 → 建草稿（post_id=34）→ 列表回查命中 → 提交审核 `PENDING` → 撤回 `WITHDRAWN` |
| 端到端（远程 Streamable HTTP，Bearer） | 无 token / 错 token → **401**；正确 token → 6 个工具、base64 上传、建稿（post_id=35）、提交 `PENDING`、列表命中 |
| 审计日志 | 每次调用一行 JSONL，含工具、账号、状态、耗时与 `post_id`；无标题正文、无密码 |

远程模式的验证方式：`--transport streamable-http --port 18766` 启动后，
`GET /mcp` 无 token 与错 token 均返回 401、正确 token 返回 400（缺少 MCP 会话头，属预期）；
再用官方 MCP 客户端带 `Authorization: Bearer` 完成 `initialize → tools/list → tools/call`，
依次调用 `upload_media(content_base64) → create_post_draft → submit_post_for_review → list_my_posts`。

端到端步骤（可复跑）：

```powershell
# 1) 启动 Java（独立 MySQL 库，端口 18080）
mvn -f server/pom.xml -DskipTests package
java -jar server/target/pixelflow-server-0.1.0.jar      # 配好 DB_URL / PIXELFLOW_JWT_SECRET

# 2) 冒烟（首次加 --register 创建服务账号）
$env:PYTHONPATH="$PWD\intelligence\src"
intelligence\.venv\Scripts\python.exe intelligence\scripts\smoke_mcp_creator.py `
    --java-url http://127.0.0.1:18080 --username mcp_creator --password <密码> --register
```

预期输出 `"ok": true`，`steps` 依次为 `upload_media / create_post_draft / list_my_posts /
submit_post_for_review / withdraw_post`。

## 依赖处理

`mcp==2.3.0` 会要求 `pydantic>=2.12`、`starlette>=0.27`、`httpx2`/`httpcore2` 等。为避免升级既有锁定版本，实际做法是：

1. 先 `uv pip sync requirements-lock.txt` 把环境恢复到锁定状态；
2. 再以 `--no-deps` 安装 `mcp` 及其**新增**依赖（`mcp-types`、`httpx2`、`httpcore2`、`jsonschema`、
   `pyjwt`、`python-multipart`、`sse-starlette`、`truststore`、`referencing`、`rpds-py`、`attrs`、`pywin32`）；
3. `requirements-lock.txt` 采用**追加段落**记录这些新增包，上面既有版本一字未改；
4. `pyproject.toml` 把 MCP 放进可选依赖组 `[mcp]`，不装 MCP 的部署不受影响。

## 与验收标准的关系

- 覆盖"Agent-Skill-MCP 是硬指标"中的 MCP 层：外部 Agent 通过标准协议调用受控业务能力；
- 覆盖"适当的系统操作权限"：独立服务账号 + 按工具 scope + 只开放创作者写操作 + 提交不等于发布；
- **未覆盖**"多模态、多轮对话、追问"——这三项发生在客户端 Agent，本轮没有自带对话界面，
  答辩时应明确说明分工，或在演示客户端（Claude Desktop / Cherry Studio）里现场多轮对话。

## 限制

- `upload_media` 读的是 MCP Server 所在机器的本地路径（stdio 模式天然满足；远程 HTTP 模式需先上传到服务端）。
- `list_my_posts` 的过滤在最近 150 条窗口内完成，未下推到 SQL。
- 工具返回 JSON 文本（`content[0].text`），未启用结构化输出 schema。
- 未实现 Agent 规划/多轮记忆；服务端只保证每次调用可控、可审计。
