# 智能搜索流式内部协议

外部端点 `POST /api/agent/search/stream`，Java 转发到 Python `POST /agent/search/stream`。两端请求均为 `question`、可选 `session_id`，沿用用户 Bearer token；Java 提供本次 `request_id` 和剩余预算（毫秒），Python不得扩大预算。外部事件 schema 见 java-api.yaml。

内部事件顺序：`meta` → 一个或多个真实 `phase` → `sources` → 零到多个 `delta` → `prepared`。任意阶段可用安全 `error` 结束。每个事件的 JSON 包含 `request_id`、从1递增的 `seq`；内部 `meta` 另含 `user_id` 供 Java 交叉校验，不转发该字段。心跳为 SSE 注释，不推进序号、不重置整体截止。

`prepared` 包含完整 `answer`、`sources`、`session_id`、`trace_id`、`degraded`，只表示 Python 校验通过，不能直接发给 Android 当成功。Java检查完整答案引用、当前来源状态及身份后，调用 `POST /agent/search/stream/confirm`，请求体为 `request_id`、`trace_id`、`digest`，使用同一 Bearer 身份。digest 为 prepared 的规范化 JSON（键排序、无额外空白、ensure_ascii=false）UTF-8 SHA-256，供确认绑定，不替代来源校验。

Python保留至多32个待确认结果，每项至多256KiB，保存至原请求截止（最多45秒），不后台继续生成、不延长请求。确认必须匹配账号、request、trace、摘要且未取消/过期。确认前重新核验来源，确认幂等，同一请求只提交一个会话轮次。完成后返回 `{request_id,trace_id,committed:true}`，Java才能向客户端发送 `done`（validated=true）。确认失败发error，部分正文不当成功。Java在断连或校验失败时调用 `DELETE /agent/search/stream/{request_id}`，同身份绑定；Python取消模型连接、清待确认结果并记失败轨迹。到达原截止时间主动清理，无需等待下次请求触发；禁止未完成答案进入成功会话。来源复核和数据库写入不占注册表全局锁；取消可在复核期间标记任务，事务提交前再次检查取消与截止状态，检查失败则回滚成功轨迹和会话。

所有内部端点保持身份检查，不公开模型密钥、提示或原始异常。确认仅处理当前内存请求的收尾，进程退出不保证恢复；不是持久化任务中心。提交成功后网络断开可能导致客户端未收到done，客户端仍按中断显示，不自动重新生成；提交幂等防止重复轮次。

整体Java预算55秒，Python最多45秒且接受更短剩余预算；空闲15秒，心跳5秒；单帧16KiB，总流256KiB。底层模型流必须正常stop并收到DONE；模型length、错误事件、格式损坏、无终止、超限均失败。可见来源发送前和完成前校验，数据库状态变更与网络发送之间不提供跨服务事务锁定；最终发现变化不得成功。

## 长文提炼分块协议

外部 `GET /api/posts/{id}/summary/stream` 对应 Python `GET /agent/posts/{id}/summary/stream`，沿用登录用户 Bearer 身份，只接受当前可见的 PUBLISHED 长文（正文至少200字）。保留原完整总结接口。事件按 `seq` 从1递增：一次 `summary`（`summary` 为1–60字完整概括），随后3–5次 `point`（`point` 为1–80字完整要点），最后 `done`（`summary`、`points`、`content_length`、UUID `trace_id`、`validated=true`）。异常以 `error`（安全 `code/message`、`incomplete=true`）结束；无done的断连同样视为未完成。

模型采用逐行JSON，仅在一个完整对象通过校验后向页面发布完整块，不展示原始JSON、不用打字动画。Python每块及结束前复核原文，Java每块及完成时核对数据库作品状态、正文与content_version。整体55秒、Python45秒、空闲15秒、心跳5秒、单帧16KiB、总量256KiB；取消关闭HTTP连接，重试由用户触发。成功或中断均记录提炼轨迹，不写搜索会话。
