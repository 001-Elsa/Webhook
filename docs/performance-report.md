# EventRelay 性能与容量报告

## 已验证结论

2026-07-22 的历史基线仅覆盖“单实例事件接入”，事件类型没有匹配
Webhook Endpoint，因此不能代表端到端投递能力：

| 场景 | 计划速率 | 实际吞吐 | P95 | P99 | 成功率 | dropped iterations |
|---|---:|---:|---:|---:|---:|---:|
| 历史稳定基线 | 50 TPS / 60s | 49.94 TPS | 220.44 ms | 300.48 ms | 100% | 0 |
| 历史过载探测 | 200 TPS / 60s | 66.21 TPS | 7.85 s | 10.11 s | 100% | 7,896 |

因此当前仍不能声称“支持 200 TPS”或把上述延迟写成端到端投递延迟。
原始历史证据保存在 `data/load-test-summary-50tps.json` 和
`data/load-test-summary.json`。

多角色（api / publisher / worker / scheduler）与批量 Outbox 框架已具备，
新架构下的容量数字 **pending measured evidence** — 仅在
`scripts/performance/run-suite.ps1` 三轮实测并写入 `docs/evidence/` 后，
才可把中位数填入下表用于对外声明。

## 结果表（待填）

列：round / TPS / P95 / P99 / error% / backlog。默认档位建议
50 / 100 / 200 / 400 TPS。数值留空表示尚未实测。

### Ingress

| rate | round | TPS | P95 | P99 | error% | backlog |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1 |  |  |  |  |  |
| 50 | 2 |  |  |  |  |  |
| 50 | 3 |  |  |  |  |  |
| 100 | 1 |  |  |  |  |  |
| 100 | 2 |  |  |  |  |  |
| 100 | 3 |  |  |  |  |  |
| 200 | 1 |  |  |  |  |  |
| 200 | 2 |  |  |  |  |  |
| 200 | 3 |  |  |  |  |  |
| 400 | 1 |  |  |  |  |  |
| 400 | 2 |  |  |  |  |  |
| 400 | 3 |  |  |  |  |  |

### Outbox

| rate | round | TPS | P95 | P99 | error% | backlog |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1 |  |  |  |  |  |
| 50 | 2 |  |  |  |  |  |
| 50 | 3 |  |  |  |  |  |
| 100 | 1 |  |  |  |  |  |
| 100 | 2 |  |  |  |  |  |
| 100 | 3 |  |  |  |  |  |
| 200 | 1 |  |  |  |  |  |
| 200 | 2 |  |  |  |  |  |
| 200 | 3 |  |  |  |  |  |
| 400 | 1 |  |  |  |  |  |
| 400 | 2 |  |  |  |  |  |
| 400 | 3 |  |  |  |  |  |

### Worker

| rate | round | TPS | P95 | P99 | error% | backlog |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1 |  |  |  |  |  |
| 50 | 2 |  |  |  |  |  |
| 50 | 3 |  |  |  |  |  |
| 100 | 1 |  |  |  |  |  |
| 100 | 2 |  |  |  |  |  |
| 100 | 3 |  |  |  |  |  |
| 200 | 1 |  |  |  |  |  |
| 200 | 2 |  |  |  |  |  |
| 200 | 3 |  |  |  |  |  |
| 400 | 1 |  |  |  |  |  |
| 400 | 2 |  |  |  |  |  |
| 400 | 3 |  |  |  |  |  |

### End-to-end

| rate | round | TPS | P95 | P99 | error% | backlog |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1 |  |  |  |  |  |
| 50 | 2 |  |  |  |  |  |
| 50 | 3 |  |  |  |  |  |
| 100 | 1 |  |  |  |  |  |
| 100 | 2 |  |  |  |  |  |
| 100 | 3 |  |  |  |  |  |
| 200 | 1 |  |  |  |  |  |
| 200 | 2 |  |  |  |  |  |
| 200 | 3 |  |  |  |  |  |
| 400 | 1 |  |  |  |  |  |
| 400 | 2 |  |  |  |  |  |
| 400 | 3 |  |  |  |  |  |

## 新的四链路容量实验

`scripts/performance/run-suite.ps1` 将每个负载档位重复三次并保留原始
证据，同时把 markdown 表片段写入 `docs/evidence/`：

1. `ingress`：鉴权、幂等检查、MySQL 事件事务写入，不匹配 Endpoint；
2. `outbox`：先停止 Publisher 形成 PENDING Outbox，再测批量抢占、并发
   confirm 和 backlog 清空时间；
3. `worker`：先停止 Worker 形成 RabbitMQ ready 消息，再测 HTTP 投递、
   状态提交和 ACK；
4. `e2e`：从 `POST /api/events` 到事件进入
   `COMPLETED/DEAD/PARTIALLY_FAILED`，记录自定义 `eventrelay_e2e_latency`。

水平扩展曲线见 `scripts/performance/run-scaling.ps1` 与
`docs/evidence/horizontal-scaling-TEMPLATE.md`。

## 采集项

每轮自动保存：

- k6 TPS、P50/P95/P99、失败率和 dropped iterations；
- Outbox 数量与最老消息年龄；
- Delivery ready/DEAD、RabbitMQ ready/consumer；
- Hikari active/pending/timeout；
- JVM CPU、GC、堆内存和进程资源；
- MySQL waiting threads、data lock waits；
- 事件接入到终态的真实端到端延迟；
- 测试前后 Prometheus 快照和 Docker stats。

## 复现

```powershell
docker compose up -d --build --wait
$env:EVENTRELAY_APP_ID = "demo-order-service"
$env:EVENTRELAY_API_KEY = $env:WEBHOOK_DEMO_PRODUCER_API_KEY
$env:EVENTRELAY_ADMIN_APP_ID = "platform-admin"
$env:EVENTRELAY_ADMIN_API_KEY = $env:WEBHOOK_DEMO_ADMIN_API_KEY
.\scripts\performance\run-suite.ps1 -Rates 50,100,200,400 -Repetitions 3
```

压测期间不要同时修改连接池、并发、批量大小和实例数。每轮只改变一个
变量，并把 Git commit、镜像 digest、实例数、CPU/内存 limit、MySQL 和
RabbitMQ 版本写入结果目录，才能形成可信的优化前后对照。
