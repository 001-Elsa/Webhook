# EventRelay

EventRelay 是一个面向外部系统集成的多租户 Webhook 投递平台，重点演示可靠事件投递、安全边界、故障恢复和可观测性，而不只是“把 HTTP 请求放进 RabbitMQ”。

## 架构与数据流

```text
Producer -> Auth/Tenant API -> MySQL(Event + Delivery + Outbox，同一事务)
                                      |
                               Outbox Publisher
                                      |
                                  RabbitMQ
                                      |
                              Lease-based Consumer
                                      |
                          Signed HTTP -> Webhook Receiver
```

MySQL 是事实来源。API 在一个事务中写入 Event、DeliveryTask 和 OutboxMessage；Outbox Publisher 使用租约抢占消息，等待 RabbitMQ correlated publisher confirm，并检测 mandatory return 后才标记 `PUBLISHED`。发布成功后、状态提交前宕机会造成重复发布，但不会丢消息。

## 可靠性模型

- 投递语义：**at-least-once（至少一次）**，不宣称 exactly-once。
- 接收端幂等键：稳定的 `X-Webhook-Delivery-Id`。接收端必须保存该 ID 并去重。
- 平台内部去重：任务租约与条件更新保证同一时刻只有一个 Worker 获得任务。
- 崩溃窗口：HTTP 已成功但 Delivery 结果尚未提交时宕机，平台会再次投递；这是至少一次语义允许的重复。
- 顺序性：默认不保证跨事件或同一 Endpoint 的严格顺序。需要顺序的业务应携带聚合版本并在接收端拒绝旧版本。
- 重试：失败后进入 5s、30s、120s TTL 队列；达到 Endpoint 最大次数后进入 DEAD。
- 恢复：数据库补偿扫描处理 Worker 锁过期、RabbitMQ 重启和重复消息场景。
- Outbox 保留：已发布记录默认保留 7 天；DeliveryAttempt 默认保留 30 天。

## 状态机

Delivery：

```text
PENDING -> RETRYING -> SUCCEEDED
    |          |
    +--------> DEAD -> RETRYING (仅人工重放)
```

`SUCCEEDED` 是终态，禁止重新投递。Event 根据其全部 Delivery 聚合为 `DISPATCHING`、`COMPLETED`、`PARTIALLY_FAILED` 或 `DEAD`；人工重放可以让失败 Event 重新进入 `DISPATCHING`。

## 安全设计

- 所有 `/api/**` 接口强制 `X-App-Id` + `X-Api-Key` 认证。
- API Key 使用 PBKDF2-HMAC-SHA256、随机盐和 210,000 次迭代保存。
- 所有查询、统计、投递详情和重放操作按认证租户过滤。
- API 使用 DTO，不直接序列化 JPA 实体；Webhook secret 不出现在任何响应中。
- Webhook secret 使用 AES-256-GCM 和随机 nonce 加密落库。
- SSRF 防护在 HTTP 客户端实际 DNS 解析阶段执行，拒绝 loopback、link-local、private、multicast 和 IPv6 ULA；内部演示 Host 必须显式加入 allowlist。
- Demo 数据默认关闭，不存在固定默认 API 凭证。

生成 256-bit AES 主密钥：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:WEBHOOK_ENCRYPTION_KEY = [Convert]::ToBase64String($bytes)
```

主密钥不得提交到仓库。生产环境应由 KMS/Vault/容器 Secret 注入并实施轮换。

## 故障处理矩阵

| 故障点 | 结果 | 恢复方式 |
| --- | --- | --- |
| 业务事务提交前崩溃 | Event、Task、Outbox 全部回滚 | Producer 重试同一 eventId |
| DB 提交后、MQ 发布前崩溃 | Outbox 保持 PENDING | Outbox Publisher 重启后继续发布 |
| MQ 确认后、Outbox 提交前崩溃 | 可能重复消息 | Delivery 租约/状态去重 |
| HTTP 调用前 Worker 崩溃 | Rabbit 未 ACK 或锁最终过期 | Rabbit 重投 + 补偿扫描 |
| HTTP 成功后、结果提交前崩溃 | 可能重复 HTTP 调用 | 接收端按 Delivery ID 幂等 |
| Redis 不可用 | 限流临时 fail-open并记录指标/日志 | Redis 恢复后自动继续 |
| RabbitMQ 不可用 | Outbox 保持 PENDING 并指数退避 | RabbitMQ 恢复后自动发布 |

## 本地启动

需要 Docker Desktop，并显式配置演示凭证和加密主密钥：

```powershell
$env:WEBHOOK_DEMO_ENABLED = "true"
$env:WEBHOOK_DEMO_ADMIN_API_KEY = Read-Host "Admin API Key"
$env:WEBHOOK_DEMO_PRODUCER_API_KEY = Read-Host "Producer API Key"
$env:WEBHOOK_DEMO_RECEIVER_SECRET = Read-Host "Receiver signing secret"
$env:EVENTRELAY_MYSQL_USER = "eventrelay"
$env:EVENTRELAY_MYSQL_PASSWORD = Read-Host "MySQL password"
$env:EVENTRELAY_MYSQL_ROOT_PASSWORD = Read-Host "MySQL root password"
$env:EVENTRELAY_REDIS_PASSWORD = Read-Host "Redis password"
$env:EVENTRELAY_RABBITMQ_USER = "eventrelay"
$env:EVENTRELAY_RABBITMQ_PASSWORD = Read-Host "RabbitMQ password"
$env:EVENTRELAY_GRAFANA_ADMIN_USER = "eventrelay-admin"
$env:EVENTRELAY_GRAFANA_ADMIN_PASSWORD = Read-Host "Grafana password"
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:WEBHOOK_ENCRYPTION_KEY = [Convert]::ToBase64String($bytes)
docker compose up -d --build
```

服务入口：EventRelay `8080`、订单 Demo `8081`、Receiver `8082`、RabbitMQ `15672`、Prometheus `9090`、Grafana `3000`。

控制台不会持久化输入的 API Key。升级旧数据库时，Flyway 会迁移字段，启动初始化器会用当前 AES 主密钥加密历史明文 Webhook secret。

## 可观测性与 SLO

- JSON 结构化日志包含 service、traceId、时间、级别、logger 和异常栈。
- Prometheus 指标覆盖投递成功/失败/耗时、最终结果、补偿恢复、认证失败、Outbox backlog/发布失败。
- Grafana 展示成功数、失败数、Outbox backlog、恢复数、P95/P99 和 Outbox 发布速率。
- 告警规则覆盖实例不可用、Outbox 积压、Outbox 发布失败、投递失败率 >5%、P99 >3s。
- 示例目标：API 可用性 99.9%，Outbox backlog 连续 5 分钟不超过 100，投递 P99（不含接收端业务耗时）按实测报告确认。

## 测试与故障演练

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd clean test
docker compose config -q
```

无 Docker 时 Testcontainers 测试自动跳过；Docker 可用时会启动真实 MySQL、Redis、RabbitMQ，并验证连接及容器重启后的恢复。

故障演练脚本：

```powershell
.\scripts\fault-drill.ps1
```

## 压测

k6 便携版可放在 `D:\Docker_Desktop\k6`。本机示例命令：

```powershell
$env:EVENTRELAY_APP_ID = "demo-order-service"
$env:EVENTRELAY_API_KEY = $env:WEBHOOK_DEMO_PRODUCER_API_KEY
$env:EVENTRELAY_RATE = "50"
$env:EVENTRELAY_DURATION = "60s"
& 'D:\Docker_Desktop\k6\k6-v2.0.0-windows-amd64\k6.exe' run `
  --summary-export=data/load-test-summary.json scripts/load-test.js
```

2026-07-22 本机单实例接入链路实测为 49.94 TPS、P95 220.44 ms、P99 300.48 ms、错误率 0%；200 TPS 目标负载会过载，不能作为容量声明。测试边界、环境和原始证据见 [性能报告](docs/performance-report.md)。

## 技术栈

Java 17、Spring Boot 3、Spring Data JPA、MySQL 8、Flyway、RabbitMQ、Redis Lua、Micrometer、Prometheus、Grafana、Testcontainers、k6、Docker Compose。
