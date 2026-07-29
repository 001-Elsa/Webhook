# EventRelay 性能与容量报告

## 已验证结论

2026-07-22 的历史基线仅覆盖单实例事件接入；事件类型没有匹配 Webhook
Endpoint，因此不能代表端到端投递能力。

| 场景 | 计划速率 | 实际吞吐 | P95 | P99 | 成功率 | dropped iterations |
|---|---:|---:|---:|---:|---:|---:|
| 历史稳定基线 | 50 TPS / 60s | 49.94 TPS | 220.44 ms | 300.48 ms | 100% | 0 |
| 历史过载探测 | 200 TPS / 60s | 66.21 TPS | 7.85 s | 10.11 s | 100% | 7,896 |

因此当前不能声称“支持 200 TPS”，也不能把上述延迟写成端到端投递延迟。
原始历史证据保存在 `data/load-test-summary-50tps.json` 和
`data/load-test-summary.json`。

## 真实端到端验收

`scripts/performance/run-suite.ps1` 默认执行 **50 / 100 / 200 TPS**、每档
连续三轮。它会创建或刷新一个专用的 `performance.e2e` Endpoint，并先提交
一条 preflight Event；若没有至少一个匹配的 Delivery 或不能进入 `COMPLETED`，
脚本会拒绝开始压测。

每轮保存：

- k6 原始 summary 和 console 输出；
- 实际提交 TPS、端到端 P95/P99、终态错误率；
- Outbox 最大积压和从压测结束到清空的时间；
- Delivery ready 最大积压和清空时间；
- Prometheus、MySQL 锁等待、RabbitMQ 队列深度、Docker stats 快照。

每次执行会生成 `docs/evidence/performance-<UTC>.md` 和对应的
`data/performance/*` 原始文件。只有三轮都完成后，才以该报告中每档的中位数
描述稳定吞吐、瓶颈和优化前后差异；绝不从历史空链路基线外推。

## 结果表（待实际运行填写）

| target TPS | round | actual TPS | E2E P95 ms | E2E P99 ms | terminal error % | Outbox max | Outbox clear s | Delivery max | Delivery clear s |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1 |  |  |  |  |  |  |  |  |
| 50 | 2 |  |  |  |  |  |  |  |  |
| 50 | 3 |  |  |  |  |  |  |  |  |
| 100 | 1 |  |  |  |  |  |  |  |  |
| 100 | 2 |  |  |  |  |  |  |  |  |
| 100 | 3 |  |  |  |  |  |  |  |  |
| 200 | 1 |  |  |  |  |  |  |  |  |
| 200 | 2 |  |  |  |  |  |  |  |  |
| 200 | 3 |  |  |  |  |  |  |  |  |

## 复现

```powershell
docker compose up -d --build --wait
$env:EVENTRELAY_APP_ID = "demo-order-service"
$env:EVENTRELAY_API_KEY = $env:WEBHOOK_DEMO_PRODUCER_API_KEY
$env:EVENTRELAY_ADMIN_APP_ID = "platform-admin"
$env:EVENTRELAY_ADMIN_API_KEY = $env:WEBHOOK_DEMO_ADMIN_API_KEY
.\scripts\performance\run-suite.ps1 -Rates 50,100,200 -Repetitions 3
```

压测期间不要同时调整连接池、并发、批量大小和实例数。每轮只改变一个变量，
并记录 Git commit、镜像 digest、实例数、CPU/内存限制以及 MySQL/RabbitMQ
版本，才能形成可信的优化对照。
