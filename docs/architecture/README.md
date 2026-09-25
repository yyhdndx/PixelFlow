# 总体架构与模块边界

## 调用关系

```text
Android app ─┐
             ├─ HTTP ─ Java server ─ 内部接口 ─ Python intelligence
Web admin ───┘              │                     │
                        业务数据库             分析数据与画像
                                                  │
                                          Agent → Skill → MCP 工具
                                                  │
                                           受控业务/分析接口
```

客户端统一访问 Java 服务。Python 接口和 MCP 工具供受信服务调用，不作为绕过用户授权的公开入口。Agent 读取业务信息时，由工具适配层调用 Java 接口；Java 提供的工具数据接口不得再次触发 Agent，以免形成递归调用。

Java 持有账号、作品、互动和审核状态。Python 管理事件聚合、画像、推荐策略和执行记录；用户身份从经过验证的请求上下文传递，不能依赖模型生成的 userId。发布、下架等状态变更由 Java 校验权限和持久化。

## 目录约定

- Android 暂时保持现有包路径，避免影响 Manifest、资源引用和 Gradle 构建。后续实现具体 Story 时逐步按业务整理，不在目录初始化阶段大规模改名。
- Java 在 `com.pixelflow.server` 下按业务划分包，每个业务内按需要设置 controller、service、repository、dto，避免创建全局大杂烩分层。
- Python 按分析、Agent 编排、Skill 和 MCP 适配职责分包。Skill 是项目运行时能力，不是开发工具的个人技能配置。
- Web 的 pages 按业务页面组织，api 负责访问 Java，components 放共享组件。
- `contracts/` 存跨边界契约；模块自己的实现类不作为共享契约。
- `tests/` 仅放跨服务测试与公共评测样本，模块单元测试就近存放。

## Story 分配

|Story|主要目录|
|---|---|
|US-01 账号与权限|server/auth、server/users、admin-web、app|
|US-02 Feed 与详情|server/content、app|
|US-03 发布与互动|server/content、server/interaction、app|
|US-04 行为与画像|server/events、intelligence/analytics、app|
|US-05 Agent 推荐|intelligence/agents、skills、mcp、server/intelligence|
|US-06 创作辅助|intelligence/agents、skills、server/intelligence、app|
|US-07 审核与管理|server/moderation、admin-web、intelligence/skills|
|US-08 反馈与评测|server/interaction、intelligence/evaluation、tests/fixtures|

表中的 server 业务名对应 `server/src/main/java/com/pixelflow/server/` 下的包；Python 业务名对应 `intelligence/src/pixelflow_intelligence/` 下的包。

## 开发顺序

先定义业务接口并实现 Java 账号、内容和 Feed，完成 Android 联调；随后接通事件与 Python 画像、推荐；再实现 Agent、Skill 注册与 MCP；最后联调 Web 审核、评测和部署。所有服务骨架目前均待实现。
