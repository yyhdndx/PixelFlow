# 跨服务契约

目录骨架，正式接口定义待实现。需求见 [软件需求说明](../docs/requirements/软件需求说明.md)。

- `http/`：Java 公共 API 与 Python 内部 API 的 OpenAPI 文件。
- `events/`：行为事件 Schema、eventId 去重规则及时间字段约定。
- `skills/`：Skill 元数据、输入输出 Schema、权限、超时和失败策略。
- `mcp/`：工具清单、参数、输出及身份传递规则。

接口文件不能只写路径；需要包含请求方法、字段、认证、错误码和有效样例。Feed 使用需求中的字段结构，补充游标时同步调整 Android 数据模型。变更应与消费方一起评审。
