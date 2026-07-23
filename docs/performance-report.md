# EventRelay 性能与容量报告

## 结论

2026-07-22 在本机 Docker Desktop 单实例环境完成了可复核的 API 接入压测。

| 场景 | 计划速率 | 实际吞吐 | 请求数 | P50 | P95 | P99 | 错误率 | 丢弃迭代 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 稳态基线 | 50 req/s，60 秒 | **49.94 TPS** | 3,001 | 99.60 ms | **220.44 ms** | **300.48 ms** | **0%** | **0** |
| 过载探测 | 200 req/s，60 秒 | **66.21 TPS** | 4,104 | 4.06 s | **7.85 s** | **10.11 s** | **0%** | **7,896** |

当前本机单实例可诚实表述为：在 50 TPS、持续 60 秒的接入负载下完成 3,001 个请求，成功率 100%，P95 220.44 ms、P99 300.48 ms。不能声称支持 200 TPS；200 TPS 目标下 VU 达到 300 上限，实际吞吐只有 66.21 TPS，并出现明显排队和 7,896 次丢弃迭代。

## 测试边界

- 请求：`POST /api/events`，包含 API Key 鉴权、Redis 幂等检查、MySQL 事件/Outbox 同事务写入以及后台 Outbox 扫描。
- 事件类型：`load.test`，没有匹配的 Webhook Endpoint。因此上述 TPS 和延迟是“事件接入链路”数据，不是接收方完成 Webhook 投递的端到端延迟。
- 所有组件均以 Docker Compose 启动并通过健康检查：平台、MySQL、Redis、RabbitMQ、receiver-mock、Prometheus、Grafana。
- 本次没有把 200 TPS 过载结果包装成容量成果；它只用于确认当前容量拐点位于 50 至 200 TPS 之间。精确最大稳态 TPS 仍需分档、多轮测试。

## 环境

| 项目 | 实测值 |
| --- | --- |
| 日期 | 2026-07-22 |
| Git 基线 | `ff0096c` 加当前工作区未提交修改 |
| Docker Desktop 配额 | 16 vCPU，7.44 GiB 内存 |
| 平台实例 | 1 |
| MySQL | 8.4.10 |
| Redis | 7.4.9 |
| RabbitMQ | 4.1.8 |
| k6 | 2.0.0，位于 `D:\Docker_Desktop\k6\k6-v2.0.0-windows-amd64\k6.exe` |

压测结束后数据库共保留 10,306 条测试事件；本次 `load.test` 没有产生 Delivery。采样结束后的平台容器内存约 1.52 GiB。该内存值是压测后的瞬时值，不是峰值，不能作为峰值资源用量。

## 原始证据

- 稳态 JSON：`data/load-test-summary-50tps.json`
- 稳态控制台输出：`data/load-test-50tps-console.txt`
- 200 TPS 过载 JSON：`data/load-test-summary.json`
- 压测脚本：`scripts/load-test.js`

k6 导出文件保留了请求数、吞吐、平均值、P95/P99、最大值、失败率和丢弃迭代，可直接复核本报告表格。过载测试运行时的旧导出格式未包含 P99 字段，10.11 秒来自该次 k6 控制台阈值统计；脚本现已显式配置后续导出 P99。

## 复现方式

先按 `.env.example` 设置随机凭证和 32 字节 Base64 AES 密钥，然后启动服务：

```powershell
docker compose up -d --build --wait

$env:EVENTRELAY_APP_ID = "demo-order-service"
$env:EVENTRELAY_API_KEY = $env:WEBHOOK_DEMO_PRODUCER_API_KEY
$env:EVENTRELAY_RATE = "50"
$env:EVENTRELAY_DURATION = "60s"
& 'D:\Docker_Desktop\k6\k6-v2.0.0-windows-amd64\k6.exe' run `
  --summary-export data/load-test-summary-50tps.json scripts/load-test.js
```

## 下一步优化实验

1. 以 10 TPS 为步长测试 60、70、80、90、100 TPS，每档重复三次取中位数，确认最大稳态吞吐。
2. 分别采集 JVM CPU/GC、Hikari 连接池等待、MySQL 慢查询和锁等待、Outbox 扫描耗时，定位 200 TPS 下的排队来源。
3. 修改压测场景，为每个事件等待对应 Delivery 进入终态，单独报告 Webhook 端到端 P95/P99。
4. 每次只调整一个变量，例如 Hikari 池、Outbox batch/fixed delay、数据库索引或平台实例数，并保留优化前后原始 JSON。

## 简历使用约束

目前可以使用“单实例 50 TPS、P95 220 ms、P99 300 ms、成功率 100%”这一组有原始文件支持的表述。不要写“支持 200 TPS”，也不要把 API 接入延迟写成 Webhook 端到端投递延迟。
