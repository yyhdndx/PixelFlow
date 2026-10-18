# 总体架构与模块边界

后续开发的接口清单、数据归属、Agent/MCP 实现顺序和验收标准见 [后续接口与实现方案](后续接口与实现方案.md)。该文档中的新增路径为待实现设计，不代表已经发布。

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

Java 已提供账号、内容、Feed、互动、事件、人工审核和权威推荐偏好的第一版实现，以及只读分析导出和本人画像代理；Python 已实现增量同步、规则画像和本人查询，以及三个只读推荐业务 Skill 与注册表。推荐预览适配层读取最近50条 Java 公开作品，执行 Skill 后再次读取公开 Feed，保守移除变化候选。推荐偏好（作品排除、作者屏蔽、个性化开关与主题抑制）已收敛到 Java 作为唯一权威来源；Python 偏好接口改为只读代理 Java 并停用本地写入，详见 [偏好统一完成说明](偏好统一-完成说明.md)。该固定流水线未接首页，推荐屏蔽不改变 Java 访问权限，也不是 Agent。Android 登录/分页/点赞/画像查询已接入，首版曝光/点击/停留遥测待设备验收。后续补齐正式推荐交付和偏好控制界面，再接 Agent/MCP；画像删除、可靠遥测、同步调度、Web 审核、评测和部署仍需完善。图中 Agent/MCP 是目标结构，不表示已经实现。
