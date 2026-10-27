# Gitee → GitHub → 阿里云自动部署

此方案不增加 Web 服务。Gitee 是唯一开发主仓库，GitHub 是单向镜像和流水线执行位置。

交付状态：已提供脚本及 Linux 模拟部署测试。2026-10-19 已完成阿里云部署账号、公钥和限定 sudo 权限配置，并验证 SSH/SCP；用户已反馈 GitHub CI 通过。GitHub 部署 Secrets 和首次真实云端自动发布尚待完成，当前运行版本未切换。详见 [服务器部署记录](服务器部署记录.md)。

## 1. 执行链路

1. `mirror-gitee.yml` 每约 5 分钟拉取 Gitee `master`，仅允许快进同步到 GitHub `master`；发现分叉就失败，不强制覆盖。GitHub 定时任务可能延迟，不保证五分钟内完成。
2. GitHub `master` push 或手动触发运行 `backend.yml`；GitHub PR 也运行测试，但不部署。
3. Ubuntu runner 使用 Java 17、Python 3.11、MySQL 8.4。创建五个独立空测试库，运行 Java 全部测试及打包、Python 全部测试和部署脚本测试；上传报告。
4. 测试全部通过后，将该次提交的 JAR、Python 源码、已测试依赖版本约束、数据库结构文件打包。部署只消费同次运行的产物，不在服务器重新拉取“最新代码”。
5. `AUTO_DEPLOY=true` 时主分支自动部署，不需要人工点击批准。部署 job 使用 `production` 环境且串行执行，不取消正在进行的发布；旧提交排队完成后如果已不是最新 master，会跳过部署。
6. SSH 上传到服务器后，验证 SHA256、提交编号、压缩包路径，取得服务器文件锁。先安装新版本依赖，再短暂停服、切换 `current`、启动 Java/Python，检查两个健康接口和 Java Feed 数据库读取。
7. 切换后失败自动恢复上一应用版本并重新检查；GitHub 仍显示失败，不因回滚成功而伪装部署成功。发布不清理旧版本、上传包、数据库或配置。

Gitee master 同步意味着 CI 发生在 Gitee 合并之后，只阻止有问题的版本部署，不会阻止 Gitee 合并。若需要合并前检查，后续接分支同步/状态回传，或迁移 PR 到 GitHub。

## 2. 创建 GitHub 镜像

创建空 GitHub 仓库，默认分支设置为 `master`。从 Gitee 的已提交版本首次推送全部 master 历史（不要在 GitHub 单独生成 README）：

```bash
git remote add github git@github.com:<GitHub账号>/pixel-flow.git
git push github master
```

以上命令只初始化镜像，不会自动提交本地修改。先将本次流水线和测试提交到 Gitee，再初始化 GitHub。以后所有代码和 workflow 修改都提交 Gitee；不要在 GitHub master 直接编辑，也不要双向同步。

在 GitHub Settings → Actions → General 允许 Actions。镜像 workflow 本身必须在默认分支。创建以下 Actions repository variables：

| Variable | 值 |
| --- | --- |
| `ENABLE_GITEE_MIRROR` | `true`，启用镜像任务 |
| `GITEE_REPOSITORY` | `git@gitee.com:yyhSEU/pixel-flow.git`（核对实际仓库路径） |
| `AUTO_DEPLOY` | 首次配置时 `false`；账号和凭据准备好后改为 `true` |

配置 repository secrets：

| Secret | 用途 |
| --- | --- |
| `GH_MIRROR_TOKEN` | 专用 GitHub fine-grained PAT，仅授权镜像仓库，Contents 和 Workflows 读写；配置有效期并定期轮换 |
| `GITEE_SSH_KEY` | Gitee 只读部署公钥对应的私钥，单独生成，不复用服务器密钥 |
| `GITEE_KNOWN_HOSTS` | 经可信渠道核对后的 Gitee SSH 主机公钥 known_hosts 记录 |

不能使用默认 `GITHUB_TOKEN` 做镜像推送，否则推送不会触发另一次 CI。PAT 需要能修改 workflow 文件，否则同步含 `.github/workflows` 的提交可能失败。GitHub master 若有分支保护，需明确允许该镜像身份推送；不要给其他人开放任意绕过权限。

## 3. 阿里云一次性准备

由管理员在现有服务器操作，不由 CI 自动执行以下初始化。部署脚本默认要求：

- `/usr/bin/python3.11` 可用并能创建 venv；Java systemd 使用 Java 17。
- `curl`、`tar`、`diff`、`cmp`、`flock`、`sha256sum`、`timeout`、`sudo` 可用。
- `/opt/pixelflow/current` 是指向 `/opt/pixelflow/releases/` 内已有版本的软链接。
- 已有 `pixelflow-java.service` 和 `pixelflow-python.service` 使用 `/opt/pixelflow/current` 下的 JAR/venv，服务运行身份仍为 `pixelflow`。
- 原环境文件、数据库配置保持不变；不要把密钥复制到发布目录。

先检查（任一步不符合请停止，不要盲目替换系统 Python）：

```bash
/usr/bin/python3.11 --version
command -v curl tar diff cmp flock sha256sum timeout sudo
readlink -f /opt/pixelflow/current
systemctl cat pixelflow-java.service pixelflow-python.service
```

创建专用部署账号。以下只调整代码目录自身的归属，不递归修改旧版本或数据库：

```bash
id pixelflow-deploy || useradd --create-home --shell /bin/bash pixelflow-deploy
usermod -aG pixelflow pixelflow-deploy
install -d -o pixelflow-deploy -g pixelflow -m 2750 /opt/pixelflow
install -d -o pixelflow-deploy -g pixelflow -m 2750 /opt/pixelflow/releases /opt/pixelflow/incoming
install -d -o pixelflow-deploy -g pixelflow-deploy -m 700 /home/pixelflow-deploy/.ssh
sudoedit /home/pixelflow-deploy/.ssh/authorized_keys
chown pixelflow-deploy:pixelflow-deploy /home/pixelflow-deploy/.ssh/authorized_keys
chmod 600 /home/pixelflow-deploy/.ssh/authorized_keys
```

在自己的电脑生成专用 Ed25519 密钥，将公钥写入上述文件，建议公钥行前加 `restrict`（禁用端口转发/PTY 等，不妨碍远程命令及文件上传）。私钥只放 GitHub environment secret。不要使用此前在聊天中提供过的 root 密码；建议另行轮换它。

通过 `visudo -f /etc/sudoers.d/pixelflow-deploy` 写入以下固定命令授权，并执行 `visudo -cf /etc/sudoers.d/pixelflow-deploy` 校验：

```sudoers
Defaults:pixelflow-deploy !requiretty
Cmnd_Alias PIXELFLOW_RELEASE = /usr/bin/systemctl stop pixelflow-python.service pixelflow-java.service, /usr/bin/systemctl start pixelflow-java.service, /usr/bin/systemctl start pixelflow-python.service, /usr/bin/systemctl stop pixelflow-analysis.timer, /usr/bin/systemctl start pixelflow-analysis.timer, /usr/bin/systemctl stop pixelflow-analysis.service
pixelflow-deploy ALL=(root) NOPASSWD: PIXELFLOW_RELEASE
```

不要授予 `sudo bash`、任意脚本执行或任意 systemctl 命令。代码发布账号能更新服务代码，仍属于高信任账号；保护私钥和主分支是必要条件。

部署脚本会暂停已经启用的分析 timer、停止正在执行的分析 oneshot，结束后恢复原先启用的 timer。不会安装或首次启用分析任务，不会重启 MySQL/Nginx。

## 4. GitHub 部署凭据和启用

创建 `production` Environment，限制 Deployment branches 为 `master`。自动发布不要设置 Required reviewers；如果以后想人工批准，再打开该设置。环境保护/环境 secrets 的可用性受 GitHub 套餐及仓库可见性限制；不支持时需要调整套餐或改为 repository secrets 并重新评估分支保护。

在该 Environment 配置 secrets：

| Secret | 值/说明 |
| --- | --- |
| `DEPLOY_HOST` | `8.140.36.87` |
| `DEPLOY_PORT` | `22` |
| `DEPLOY_USER` | `pixelflow-deploy`，流水线拒绝 root |
| `DEPLOY_SSH_KEY` | 专用部署私钥全文 |
| `DEPLOY_KNOWN_HOSTS` | 已核验的服务器 SSH 主机公钥记录（非默认端口使用 `[IP]:端口` 格式） |

服务器管理员可读取 `/etc/ssh/ssh_host_ed25519_key.pub` 的公钥，与工作站采集的 known_hosts 指纹核对。不要在 CI 中临时 `ssh-keyscan` 后不核验就信任，也不要关闭 StrictHostKeyChecking。

阿里云安全组需要允许 runner 访问 SSH 端口。GitHub 托管 runner 出口 IP 不是固定单地址；不要为方便开放 MySQL 或 Python 端口。若不接受动态来源 SSH，可后续使用固定出口 runner/私网连接，但不能把可执行不可信 PR 的 runner 装在业务服务器上。

先保持 `AUTO_DEPLOY=false`，手动运行 Backend CI，确认目标版本测试通过；然后设为 `true` 再运行一次主分支 workflow。之后 Gitee master 更新经镜像同步，即自动测试和部署。Actions 页面保存各步骤日志及测试报告。

暂停后续自动部署：将 `AUTO_DEPLOY=false`。这不会取消已经开始的发布，正在部署时不要强制终止进程，应观察其完成/回滚。

## 5. 失败、兼容性和限制

- SHA256 错误、包路径不安全、依赖安装失败、结构文件变化，均在停服前失败，当前服务不变。
- Java MySQL 迁移目录或 Python `analytics/mysql_schema.py` 与当前版本不同，会拒绝自动发布。包含迁移的版本须先备份、评审兼容性并人工部署；不通过环境变量绕过该门禁。
- 门禁不是完整兼容性证明：修改其他位置的数据写入语义同样必须保证旧代码仍能读取。应用回滚不回滚数据库，也不会撤销新版本已处理的业务请求。
- 健康检查验证两服务存活及 Java Feed 可读取数据库，不代表 Python 同步、画像计算或 Android 全流程验收；这些仍依赖测试和后续业务检查。
- 暂停服务切换会造成短暂不可用，不是零停机部署。上传包、失败版本和旧版本均保留，需要后续制定磁盘告警与保留策略。
- CI 固定官方 action 的提交 SHA；MySQL 使用 `8.4` 系列标签，Python 发布安装使用本轮 CI 生成的约束文件。依赖安装依然需要服务器访问包源；下载失败不会切换应用。
- SSH 断线/正常终止会尽量触发回滚；机器断电、SIGKILL、磁盘故障无法由脚本保证自动恢复。回滚失败会明确报错，需管理员排查。

## 6. 本地验证

Linux/WSL 下运行：

```bash
bash -n deploy/scripts/deploy-release.sh
python3 -m unittest discover -s tests/deploy -v
```

测试使用临时目录和模拟 systemd/curl/venv，不访问真实服务器：覆盖成功发布、健康失败回滚、结构变化阻断、校验和错误、目录穿越、并发锁、依赖安装失败、分析 timer 暂停与恢复。2026-10-19 在 WSL Ubuntu 中 8 项通过；两个 workflow 通过 actionlint v1.7.7 校验（未启用 ShellCheck 集成），发布脚本通过 `bash -n`。这不是实际阿里云发布记录，也不是 GitHub Actions 已运行的证明。

官方参考：[部署环境与保护规则](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)、[GITHUB_TOKEN 触发工作流限制](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。
