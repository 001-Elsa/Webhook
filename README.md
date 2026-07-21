# EventRelay：可靠事件投递与通知平台

EventRelay 是一个面向外部系统集成场景的可靠事件投递基础设施。业务系统提交事件后，平台负责持久化、异步投递、签名、限流、失败重试、死信治理和可观测性。

## 已真实落地的技术栈

- Java 17、Spring Boot 3、Spring Data JPA、Flyway
- MySQL 8.4：事件、端点、投递任务和投递审计日志的事实来源
- RabbitMQ 4：实时异步消费、手动 ACK、TTL + DLX 延迟重试和死信队列
- Redis 7：Lua 原子分布式限流、24 小时幂等键缓存
- Prometheus + Grafana：成功数、失败数、投递耗时和 JVM 指标
- Docker Compose：8 个服务一键启动
- JUnit 5 + Testcontainers：使用真实 MySQL、RabbitMQ、Redis 的集成测试
- GitHub Actions：自动编译、测试和校验 Compose

## 可靠性设计

1. API 在同一 MySQL 事务内写入事件和投递任务。
2. 事务提交后发布 RabbitMQ 消息，避免消费者读取到未提交任务。
3. 消费者先用条件更新抢占任务，多实例或重复消息只有一个实例能够执行。
4. HTTP 成功后记录审计日志并更新任务；消息采用手动 ACK。
5. 失败任务按 5 秒、30 秒、120 秒进入 TTL 重试队列，再由 DLX 路由回主队列。
6. 超过端点最大次数后同时保留 MySQL `DEAD` 状态并写入 RabbitMQ 死信队列，可人工重放。
7. MySQL 是事实来源；定时补偿扫描会重新发布到期任务，覆盖“数据库已提交但 MQ 发布失败”和 MQ 重启场景。
8. 投递语义是 at-least-once。平台以任务状态和乐观锁防止内部重复执行；模拟接收端以 `deliveryId` 演示消费幂等。

事件幂等以 MySQL `(tenant_id, event_id)` 唯一索引为最终约束，Redis 仅作短期辅助，Redis 故障不会破坏正确性。限流使用 Lua 将 `INCR` 与过期设置合并为原子操作；Redis 不可用时暂时 fail-open 并记录警告，避免基础设施故障阻断全部投递。

投递尝试日志默认保留 30 天，每天 03:30 清理；可通过 `DELIVERY_ATTEMPT_RETENTION_DAYS` 调整，清理数量暴露为 Prometheus 指标。

## 一键启动

只需要安装并启动 Docker Desktop，不需要在 Windows 单独安装 MySQL、Redis、RabbitMQ、Prometheus 或 Grafana。项目会自动拉取官方镜像：

```powershell
docker compose up -d --build
docker compose ps
```

首次启动需要下载镜像和 Maven 依赖，耗时取决于网络。服务入口：

| 服务 | 地址 | 凭据 |
| --- | --- | --- |
| EventRelay 控制台 | http://localhost:8080 | - |
| Swagger | http://localhost:8080/swagger-ui.html | - |
| 订单演示 | http://localhost:8081 | - |
| 接收端模拟器 | http://localhost:8082 | - |
| RabbitMQ 管理台 | http://localhost:15672 | `eventrelay / eventrelay` |
| Prometheus | http://localhost:9090 | - |
| Grafana | http://localhost:3000 | `admin / admin` |

停止服务：

```powershell
docker compose down
```

如需同时删除本项目的数据库和监控卷，明确执行 `docker compose down -v`。

## API 示例

```powershell
$body = @{
  eventId = "evt-10001"
  type = "order.paid"
  data = @{ orderId = "10001"; amount = 99.9 }
} | ConvertTo-Json

Invoke-RestMethod http://localhost:8080/api/events -Method Post `
  -Headers @{ "X-App-Id"="demo-order-service"; "X-Api-Key"="order-key" } `
  -ContentType application/json -Body $body
```

再次提交相同 `eventId` 会返回 `duplicate=true`，且不会产生第二组投递任务。

## 测试与压测

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd verify
docker compose config -q
```

安装 k6 后可运行 `k6 run scripts/load-test.js`。脚本默认以 200 次/秒持续写入 60 秒。仓库不预填虚构 TPS；请将机器配置、Git 提交、参数、成功率、p95 延迟和最终任务状态写入实测报告后再用于简历。

建议验证的故障场景：重复事件、接收端连续失败、平台重启、多平台实例竞争、RabbitMQ 重启、Redis 断开、死信批量重放。

## 模块

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `notification-platform` | 8080 | 事件接收、可靠调度、管理 API 和指标 |
| `demo-order-service` | 8081 | 产生订单领域事件 |
| `receiver-mock` | 8082 | 校验 HMAC 签名、模拟失败和幂等消费 |

演示凭据为 `platform-admin/admin-key` 和 `demo-order-service/order-key`，仅用于本地。生产环境必须改成密文存储、轮换机制和正式认证系统。
