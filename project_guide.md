# Project Guide: EventRelay 可靠事件投递与通知平台

这份文档用于说明项目定位、核心链路、可靠性设计、测试方式和当前真实验证结果。它不是简历话术，而是后续开发、面试复盘和压测报告的工程依据。

## 1. 项目定位

EventRelay 是一个面向外部系统集成场景的可靠事件投递平台。

它解决的问题不是“怎么写一个 Webhook 接口”，而是：

- 业务事件如何可靠保存
- 如何异步投递到外部系统
- 接收端失败后如何重试
- 多实例部署时如何避免重复执行
- RabbitMQ 故障或重启后如何补偿恢复
- 重复投递时如何通过幂等保证业务安全
- 如何通过测试证明这些机制真的可用

当前仓库包含三个 Spring Boot 模块：

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `notification-platform` | 8080 | 核心事件投递平台 |
| `demo-order-service` | 8081 | 模拟业务系统，产生订单事件 |
| `receiver-mock` | 8082 | 模拟外部 Webhook 接收端 |

## 2. 技术栈

| 组件 | 用途 |
| --- | --- |
| Java 17 | 运行环境 |
| Spring Boot 3.3.5 | 应用框架 |
| Spring Data JPA | 数据访问 |
| MySQL 8 | 事件、端点、任务、投递日志的事实来源 |
| Flyway | 数据库迁移，替代 H2 / `ddl-auto:update` |
| RabbitMQ | 异步投递、手动 ACK、TTL + DLX 延迟重试、死信队列 |
| Redis + Lua | 分布式限流、幂等键缓存 |
| Prometheus | 指标采集 |
| Grafana | 监控面板 |
| Docker Compose | 本地一键部署 |
| JUnit 5 + Testcontainers | 单元测试和真实基础设施集成测试 |
| GitHub Actions | CI 验证 |

## 3. 核心投递链路

以订单服务提交 `order.paid` 事件为例：

1. `demo-order-service` 调用 `POST /api/events`。
2. `ApiAuthFilter` 校验 `X-App-Id` 和 `X-Api-Key`。
3. `EventService.submit()` 在 MySQL 事务中保存 `EventRecord` 和 `DeliveryTask`。
4. 事务提交后通过 `TransactionSynchronization.afterCommit()` 发布 RabbitMQ 消息。
5. `DeliveryMessageConsumer` 手动 ACK 消息。
6. `DeliveryService.processDelivery()` 用数据库条件更新抢占任务。
7. 抢占成功后，事务外发起 HTTP Webhook 调用。
8. HTTP 返回后，短事务保存 `DeliveryAttempt` 和 `DeliveryTask` 状态。
9. 失败但未达到最大次数时，进入 RabbitMQ TTL 重试队列。
10. 达到最大次数后，任务进入 MySQL `DEAD` 状态，并发布到死信队列。
11. 补偿扫描器定期扫描 MySQL 中到期的 `PENDING / RETRYING` 任务，重新发布到 RabbitMQ。

## 4. 正确性改造

当前版本已经修复以下容易被面试追问的问题。

### 4.1 多租户统计隔离

重复事件判断使用 `(tenant_id, event_id)` 唯一键。

重复事件返回投递任务数时，已经从：

```java
countByEventEventId(eventId)
```

改为：

```java
countByEventTenantIdAndEventEventId(tenantId, eventId)
```

避免不同租户使用相同 `eventId` 时统计串数据。

同时，端点列表和事件列表也改为按当前租户查询，避免 `findAll()` 暴露跨租户数据。

### 4.2 事务提交后再发送 MQ

首次提交事件、人工重试、批量死信重放都统一使用 `afterCommit` 发布 RabbitMQ 消息。

这样可以避免：

- MQ 消息先发出
- 数据库事务还没提交
- 消费者读到旧状态或读不到任务

### 4.3 外部 HTTP 不占用长事务

`processDelivery()` 已拆成三个阶段：

1. 短事务抢占任务
2. 事务外调用外部 Webhook
3. 短事务保存投递结果

这样减少数据库连接和锁被慢 HTTP 接收端长期占用的风险。

### 4.4 避免 RabbitMQ 热循环

消费者遇到未知异常时，不再直接：

```java
basicNack(tag, false, true)
```

而是尽量投递到应用死信队列并 ACK 当前消息。

如果死信投递本身失败，再 `basicNack(tag, false, false)`，避免同一条坏消息立即无限重新入队。

### 4.5 HTTP 超时

新增统一的 Webhook HTTP 客户端超时配置：

```yaml
webhook:
  http:
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
```

用于避免外部接收方慢响应导致投递线程长期阻塞。

### 4.6 Webhook URL SSRF 防护

创建和更新 Webhook Endpoint 时会校验 URL：

- 只允许 `http` 和 `https`
- 禁止 userinfo
- 禁止 `localhost`
- 禁止 loopback、site-local、link-local、multicast、IPv6 unique-local 地址
- 域名会解析后检查所有返回地址

注意：演示数据初始化器不走外部用户输入入口，因此 Docker Compose 内部服务名仍可用于本地演示。

## 5. RabbitMQ 拓扑

RabbitMQ 使用三组 Exchange / Queue：

```text
delivery.exchange
  -> delivery.queue

retry.exchange
  -> retry.5s.queue    TTL 5s    DLX -> delivery.exchange
  -> retry.30s.queue   TTL 30s   DLX -> delivery.exchange
  -> retry.120s.queue  TTL 120s  DLX -> delivery.exchange

dead.exchange
  -> dead.queue
```

核心语义：

- 主队列消费使用手动 ACK。
- 失败后不在消费者线程里 sleep。
- 延迟由 RabbitMQ TTL + DLX 实现。
- MySQL 是任务状态事实来源。
- RabbitMQ 负责异步通知和削峰。
- 补偿扫描用于覆盖 MQ 发布失败或 RabbitMQ 重启后的恢复场景。

## 6. Redis 设计

Redis 当前用于两类短期状态：

### 6.1 Lua 分布式限流

`RedisRateLimiter` 使用 Lua 保证 `INCR + EXPIRE` 的原子性。

Redis 不可用时当前策略是 fail-open，即放行请求，并记录 warn 日志。这样不会因为 Redis 抖动导致核心投递完全不可用。

### 6.2 幂等键缓存

`EventIdempotencyStore` 使用 Lua `SETNX + EXPIRE` 写入 24 小时幂等键。

Redis 只是快速路径，最终幂等仍以 MySQL `(tenant_id, event_id)` 唯一索引为准。

## 7. 自动化测试

当前 `notification-platform` 已有以下测试。

| 测试类 | 覆盖内容 |
| --- | --- |
| `SignatureServiceTest` | HMAC-SHA256 签名和验签 |
| `InfrastructureIntegrationTest` | Testcontainers 启动 MySQL、RabbitMQ、Redis，并验证真实连接 |
| `EventServiceReliabilityTest` | 多租户重复统计、事务提交后发 MQ |
| `DeliveryServiceReliabilityTest` | 补偿扫描、人工重试 afterCommit、失败重试、最大次数后 DEAD |
| `RabbitQueueReliabilityTest` | RabbitMQ 5/30/120 秒重试路由、未知异常不 requeue 热循环 |
| `RedisReliabilityTest` | Redis 故障时限流 fail-open |
| `WebhookUrlValidatorTest` | SSRF 防护规则 |
| `EndpointControllerTenantIsolationTest` | Endpoint 列表和更新的租户隔离 |
| `EventControllerTenantIsolationTest` | Event 列表的租户隔离 |

已覆盖的关键场景：

- 相同事件重复提交时按当前租户统计任务数
- 数据库事务提交后才发布 MQ
- 人工重试事务提交后才发布 MQ
- MySQL 补偿扫描会重新发布到期任务
- 投递失败后进入 5 秒重试窗口
- 达到最大次数后进入 `DEAD`
- RabbitMQ 重试路由到 5s / 30s / 120s 队列
- 未知异常不立即重新入队
- Redis 故障时限流降级
- Webhook URL SSRF 拦截
- Endpoint / Event 查询租户隔离

## 8. 本地测试记录

测试时间：2026-07-21

本机没有安装全局 `mvn`，仓库也没有 `mvnw`。本次使用 Docker 中的 Maven 镜像运行测试，避免往 C 盘安装 Maven。

Docker Desktop 已启动，Docker Server 版本：

```text
29.6.1
```

Maven 缓存目录挂载到：

```text
D:\Docker_Desktop\maven-repository
```

最终通过的命令：

```powershell
$repo = 'D:\Docker_Desktop\maven-repository'
docker run --rm `
  -e TESTCONTAINERS_RYUK_DISABLED=true `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  -v "D:\WebHook:/workspace" `
  -v "${repo}:/root/.m2" `
  -v /var/run/docker.sock:/var/run/docker.sock `
  -w /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn -pl notification-platform test
```

结果：

```text
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:37 min
```

说明：

- 第一次运行时，测试编译暴露出 `RedisReliabilityTest` 的 Mockito 重载匹配歧义，已修复。
- Docker Maven 容器中运行 Testcontainers 时需要挂载 Docker socket。
- Windows Docker Desktop 场景下，需要设置 `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`，否则容器内 Maven 进程可能无法访问宿主映射端口。
- 本地禁用了 Ryuk：`TESTCONTAINERS_RYUK_DISABLED=true`。因此本地跑完后应手动清理 Testcontainers 残留容器。

## 9. Docker Compose

项目提供 `docker-compose.yml`，目标是一键启动完整链路：

```text
notification-platform
demo-order-service
receiver-mock
mysql
redis
rabbitmq
prometheus
grafana
```

启动命令：

```bash
docker compose up -d
```

常用地址：

| 服务 | 地址 |
| --- | --- |
| notification-platform | http://localhost:8080 |
| demo-order-service | http://localhost:8081 |
| receiver-mock | http://localhost:8082 |
| RabbitMQ Management | http://localhost:15672 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

## 10. 仍需补的内容

当前项目已经补齐第一优先级的正确性问题和一批故障测试，但还不能在简历里写虚构性能数字。

下一步应做：

1. 正式压测并产出 `benchmark-report.md`。
2. 记录测试机器配置、Docker CPU/内存、请求速率、总事件数、成功率、p50/p95/p99。
3. 记录 RabbitMQ 堆积峰值和积压清空时间。
4. 对比单实例和双实例投递表现。
5. 补架构图、消息时序图、ADR。
6. 后续通过 Issue / 分支 / PR / 小 commit 记录真实工程过程。

## 11. 面试时可以讲的关键点

- MySQL 是事实来源，RabbitMQ 是异步通知通道。
- 系统语义是 at-least-once，不承诺 exactly-once。
- 重复投递通过接收端幂等和平台侧幂等键降低影响。
- MQ 发布失败或重启后，补偿扫描从 MySQL 恢复任务。
- 多实例竞争任务通过数据库条件更新抢占。
- 外部 HTTP 不放在长事务中，避免慢接收端拖垮数据库连接池。
- 未知异常不 requeue 热循环，避免坏消息打爆消费者。
- Redis 故障时限流 fail-open，MySQL 唯一索引仍保证最终幂等。
