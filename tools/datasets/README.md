# WuKong 私有研究语料导入

从 WuKong 官方压缩包读取有限范围，抽取中文图文描述，检查图片链接后导入独立 MySQL 数据库 `pixelflow_corpus`。Python 3.11+，标准库，无需安装第三方 Python 包；导入需要本机 MySQL 8 客户端和支持 ngram 的 MySQL 8 服务端。

数据来源：[官方下载页](https://wukong-dataset.github.io/wukong-dataset/download.html)，来源许可及附加条款见 [WuKong 使用条款](https://wukong-dataset.github.io/wukong-dataset/terms_of_use.html)。原始数据包、逐条检查结果和数据库备份保存在受限私有目录，不提交 Git、不提供数据集下载入口。研究导入器本身不创建帖子；2026-10-30 按用户确认的课设原型演示用途，另行通过下文的显式发布工具展示 961 条图文。该操作记录不改变来源条款。

## 字段适配

| 悟空数据 / 派生字段 | 研究库字段 | 约束及用途 |
| --- | --- | --- |
| caption 原文 | original_caption | 完整保留来源描述；超出 TEXT 字节容量则拒收 |
| 规范化 caption | title / content | 对齐业务 VARCHAR(120) / VARCHAR(5000)，标题截断，超长正文拒收 |
| url | source_url / clips_json | 图像 clip 的 type=0；未获得的宽高为 null |
| 来源 CSV + 行号 | source_file / source_row / source_record_id | 可追溯的行身份与 SHA-256 去重键 |
| 图片 URL + 规范化正文 | content_hash | 跨批次内容去重 |
| 七类关键词规则 | topics_json / rule_version | 多标签兴趣主题，供内容召回基线使用 |
| 网络检查 | resource_status / resource_check_json | REACHABLE、UNREACHABLE、BLOCKED，含检查时间 |
| 数据集信息 | license / terms_url / status | 固定 RESEARCH_ONLY，与公共内容隔离 |
| 导入时间 | ingested_at | Unix 秒；不是原始发布时间 |
| 未提供的作者、发布时间 | author_id / source_create_time | 保持 null；不虚构用户、点赞或行为 |

`corpus_batches` 保存批次与文件摘要，`corpus_batch_items` 保存批次成员关系。重复执行同批次不增量，相同内容跨批次复用；回滚只移除该批次不再被其他批次引用的数据。业务库与 Flyway 迁移不受影响。研究记录缺少真实作者和图片尺寸，不能直接作为现有帖子接口的响应对象。

## 操作步骤

以下示例在服务器私有目录运行。`--defaults-file` 必须是已有受限 MySQL 凭证文件，不在命令行提供密码；研究使用方以后应使用仅有研究库 SELECT 权限的独立账户。

```bash
# 所有原始输出放在仓库和 Web 根目录之外，先创建权限为 700 的目录。
umask 077
python wukong_corpus.py --output /root/private-wukong-new --count 1000
python check_resources.py --records /root/private-wukong-new/records.jsonl \
  --output /root/private-wukong-new/resource-checks.json

# 单元测试；MySQL 集成验证自动创建并删除专属临时库。
python -m unittest discover -s tools/datasets -p 'test_*.py' -v
python verify_mysql.py --defaults-file /etc/pixelflow/mysql-admin.cnf

# 首次不带 --apply 为预览；核对结果并完成备份后，同一命令加 --apply 执行。
python import_corpus.py --defaults-file /etc/pixelflow/mysql-admin.cnf \
  --database pixelflow_corpus --batch wukong-20261030-1000 \
  --records /root/pixelflow-corpus-20261030/records.jsonl \
  --manifest /root/pixelflow-corpus-20261030/manifest.json \
  --checks /root/pixelflow-corpus-20261030/resource-checks.json

# 回滚预览；增加 --apply 才会删除该批次。
python import_corpus.py --defaults-file /etc/pixelflow/mysql-admin.cnf \
  --database pixelflow_corpus --batch wukong-20261030-1000 --rollback
```

导入器拒绝业务库名称、未知数据库归属、校验和错误、重复来源、篡改的规范化字段以及缺失检查结果。SQL 文本以 UTF-8 十六进制传入；批次及文档写入使用事务。保持同一时间只有一个操作员执行导入或回滚；发生冲突会报错并回滚，不提供多写者调度。

首次样本来自 4 个等间隔分片，按七类主题筛选，每片 250 条；不是随机样本或代表性评测集。将 `--count` 调为 10000 可在新目录重新抽样，使用新批次号导入并复用既有内容；实际新增数量以预览为准，不保证主题分布均衡。网络读取预算默认 32 MiB，超限会中止，需要先评估后调整。

## 研究查询

在私有研究环境连接 `pixelflow_corpus`，查询可用于基于内容的推荐候选、图片描述检索与 RAG 检索原型：

```sql
SELECT id, title, content, source_record_id, topics_json
FROM corpus_documents
WHERE status = 'RESEARCH_ONLY' AND resource_status = 'REACHABLE'
  AND MATCH(title, content) AGAINST('咖啡' IN BOOLEAN MODE)
LIMIT 20;
```

中文全文索引已建立；尚未生成向量、接入推荐模型或 RAG 服务。短图文描述适合检索原型，不等同于经过核实的长文本知识库，也不能替代真实交互行为。来源文本须作为不可信数据处理，不能作为模型系统指令执行。

REACHABLE 只表示检查时 HTTP 成功、图片 MIME 和前 1 KiB 文件签名符合预期，不代表完整解码、尺寸校验、内容审核、永久可用或素材再分发授权。未下载保存完整图片。首次运行的检查元数据使用了旧名称 HTTP_HEADER_ONLY，实际执行的也是响应头加前缀检查，后续脚本已改为 HTTP_HEADER_AND_PREFIX。

首次执行结果见 [2026-10-30 验收记录](../../docs/testing/WuKong研究语料导入-2026-10-30.md)。

## 课设原型信息流展示

`publish_course_demo.py` 是独立手动入口，不随研究导入、启动或部署自动执行。先用 `prepare_demo_feed.py` 再次读取图片头部，取得真实宽高；每个响应最多读取 512 KiB，不保存图片文件。仅选取原检查 REACHABLE、尺寸合法且 URL 满足业务字段限制的记录。首批 967 个候选中 961 个通过，39 条未进入信息流。

演示发布会向 `pixelflow_app.posts` 新增帖子，使用专属样本作者“悟空图文样本”。作者没有可登录密码，用户名属于应用保留前缀。发布时间表示导入演示的时间；不新增点赞、收藏或行为事件。研究库的 `corpus_demo_publications` 保存来源记录到帖子 ID 的对应关系，`seed_batches` 保证同批次重放不重复发布。

```bash
python prepare_demo_feed.py \
  --records /root/pixelflow-corpus-20261030/records.jsonl \
  --checks /root/pixelflow-corpus-20261030/resource-checks.json \
  --output /root/pixelflow-corpus-20261030/demo-dimensions.json

# 临时数据库测试：事务失败回滚、重放、真实尺寸、下架和原帖子保留。
python verify_course_mysql.py --defaults-file /etc/pixelflow/mysql-admin.cnf

# 发布预览。先备份业务库与研究库，增加 --apply 才实际发布。
python publish_course_demo.py --defaults-file /etc/pixelflow/mysql-admin.cnf \
  --directory /root/pixelflow-corpus-20261030

# 批量下架预览；增加 --apply 才执行。保留帖子与已有互动记录。
python publish_course_demo.py --defaults-file /etc/pixelflow/mysql-admin.cnf --withdraw
```

本批次固定为 `wukong-course-demo-20261030`。输入变化会拒绝复用批次；批量下架后重放不会重新上架。继续扩容或重新上架需另行实现明确的批次操作。不要通过删除研究语料替代帖子下架。

展示结果与备份位置见 [课设信息流展示验收](../../docs/testing/WuKong课设信息流展示-2026-10-30.md)。
