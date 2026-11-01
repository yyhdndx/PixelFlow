## 任务
issue IKORVG：为事件同步和画像重算提供可恢复的周期调度。

## 改动
- `intelligence/src/pixelflow_intelligence/scheduled_maintenance.py`（新增入口，`python -m pixelflow_intelligence.scheduled_maintenance`）：复用现有 `maintenance.run` 与配置校验/HTTP 客户端约束，只负责调度状态、互斥与退出码；现有手动 CLI 行为不变。
- `intelligence/tests/test_scheduled_maintenance.py`（新增 18 个测试）。
- `deploy/systemd/pixelflow-analysis.service`、`deploy/systemd/pixelflow-analysis.timer`（新增模板）。
- `deploy/分析调度使用说明.md`（新增）：安装、首次手动运行、启停、日志、失败重试、状态备份与恢复。

## 新增配置 / 文件权限 / 调度间隔 / 超时
- 环境变量：沿用 `PIXELFLOW_MAINTENANCE_URL`、`PIXELFLOW_INTELLIGENCE_TOKEN`；新增 `PIXELFLOW_MAINTENANCE_STATE_DIR`（默认 `/var/lib/pixelflow/python/maintenance`，systemd 下由 `StateDirectory=pixelflow/python/maintenance` 提供）。
- `/etc/pixelflow/analysis.env`：root:pixelflow 0640，由 systemd 注入；unit、命令参数、日志、状态文件中均无 token。
- 调度：timer `OnBootSec=2min` 首次触发，`OnUnitActiveSec=5min` 周期；单轮默认预算 120 秒（`--budget-seconds 120`），`TimeoutStartSec=180` 略大于预算；`SuccessExitStatus=2`（分页截断不算服务故障），不设无间隔失败重启；退出码 0=完成 / 2=仍有待处理 / 1=失败 / 3=锁忙。
- 状态文件 `state.json`：`{"version":1,"refresh_after":...,"last_run_at":...,"last_status":...}`，pixelflow 私有，临时文件 + `os.replace` 原子写；损坏时报 `CORRUPT_STATE_FILE` 退出 1 并提示恢复办法，不静默清空。
- 锁：状态目录 `.scheduled.lock`（O_CREAT|O_EXCL + PID，stale PID 自动清理），防止 timer 与手动包装器并发。

## 游标语义
- 仅成功响应（退出 0 重置为空、退出 2 持久化新游标）才写状态；异常/超时/中断不推进游标，已确认页面可安全重放（依赖既有重算幂等）。
- JSON 日志显式区分 complete / incomplete，不伪装追平；事件同步仍以 MySQL checkpoint 为唯一来源，状态文件不复制 checkpoint、不存用户明细。

## 测试
- `cd intelligence && uv run --extra test python -m pytest -q`：新增 18/18 通过；全量 121 passed, 4 skipped（跳过为既有的 MySQL 集成类测试）。
- 覆盖：空数据、从头扫描、incomplete 续跑不跳页、incomplete 游标未前进时保留旧值、complete 归零、失败/异常/超时不推进游标、进程重启续跑、损坏 JSON/版本不符报错、stale PID 锁替换、锁忙退出 3、真实并发一跑一退 3、原子写、日志无 token、状态目录覆盖。

## 验收状态说明
- **“模板已验证”与“云端已启用”是不同状态**：本 PR 只交付代码与模板。`systemd-analyze verify` 与真实 Linux 上的首次/周期触发、停止、重启、超时验证需在部署环境由部署负责人执行（本开发机为 Windows，无法代替 systemd 验收）；云端 timer 的启用不在本 PR 范围。

## 手动触发示例
```
sudo -u pixelflow \
  PIXELFLOW_MAINTENANCE_STATE_DIR=/var/lib/pixelflow/python/maintenance \
  /opt/pixelflow/current/intelligence/.venv/bin/python \
  -m pixelflow_intelligence.scheduled_maintenance
```
输出 JSON 含 `status/cursor_before/cursor_after/duration/exit_code`；查看日志：`journalctl -u pixelflow-analysis.service`。
