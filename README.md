# Reliable Event Notification Platform

这是一个面向 Java 后端岗位展示的可靠事件通知平台。核心项目不是商城，而是一个独立运行的通用 Webhook 投递系统：业务系统提交事件，平台负责保存、异步投递、失败重试、签名、防重、死信和人工重放。

## 模块

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `notification-platform` | `8080` | 核心可靠事件通知平台 |
| `demo-order-service` | `8081` | 迷你订单服务，只负责产生订单事件 |
| `receiver-mock` | `8082` | 模拟商家接收服务，可配置失败次数 |

## 第一阶段能力

- Webhook 端点管理：`POST /api/endpoints`
- 事件接收与幂等：`POST /api/events`
- 异步投递任务：事件和投递任务分表保存
- 自动重试：指数退避，超过最大次数进入死信
- 请求签名：`HMAC-SHA256`，通过 `X-Webhook-Signature` 传递
- 投递日志：每次尝试保存响应、错误和耗时
- 人工重放：`POST /api/deliveries/{id}/retry`
- 简单限流：按端点进行每分钟内存限流
- 监控：Spring Boot Actuator 暴露 `health/info/metrics`
- 管理页面：平台首页可查看统计、任务、日志和手动重放

## 已升级能力

- MQ 扩展点：`DeliveryQueue` 接口隔离投递队列，当前实现为数据库队列，后续可替换 RabbitMQ/Kafka/Redis Stream。
- 分布式任务抢占：`DeliveryTask` 增加 `lockedBy/lockedUntil/version`，调度前先抢占任务，降低多实例重复投递风险。
- 租户与应用身份：`ApplicationClient` 保存 `tenantId/appId/apiKey/role`，事件和端点都绑定租户。
- API 鉴权与 RBAC：写接口要求 `X-App-Id` 和 `X-Api-Key`，区分 `ADMIN/PRODUCER/VIEWER`。
- 分布式限流预留：`RateLimiter` 接口隔离限流实现，当前为内存限流，并提供 Redis Lua 方案说明。
- Prometheus 指标：暴露 `/actuator/prometheus`，记录投递成功数、失败数和耗时。
- 链路追踪：支持 `X-Trace-Id`，事件、投递请求和日志 MDC 都携带 traceId。
- 死信治理：支持死信列表 `GET /api/deliveries/dead-letter` 和批量重放 `POST /api/deliveries/dead-letter/replay`。
- OpenAPI 文档：访问 `http://localhost:8080/swagger-ui.html` 查看接口文档。
- SDK 示例：`notification-platform/src/main/resources/static/sdk-example.js` 提供 JS 调用示例。

## 快速演示

1. 启动三个服务。
2. 打开模拟接收服务 `http://localhost:8082`，确认“接下来失败次数”为 `1`。
3. 打开订单服务 `http://localhost:8081`，创建订单并点击“支付”。
4. 打开平台 `http://localhost:8080`，观察投递任务第一次失败，随后自动重试并成功。

平台启动时会自动创建一个演示端点：

```text
http://localhost:8082/webhook/demo-merchant
secret: demo-secret
eventTypes: order.created,order.paid,order.cancelled,order.shipped
```

## API 示例

提交事件：

```http
POST http://localhost:8080/api/events
Content-Type: application/json
X-App-Id: demo-order-service
X-Api-Key: order-key
X-Trace-Id: order-10001

{
  "type": "order.paid",
  "data": {
    "orderId": "10001",
    "amount": 99.9
  }
}
```

创建端点：

```http
POST http://localhost:8080/api/endpoints
Content-Type: application/json
X-App-Id: platform-admin
X-Api-Key: admin-key

{
  "name": "merchant-a",
  "url": "http://localhost:8082/webhook/merchant-a",
  "secret": "demo-secret",
  "eventTypes": "order.paid,order.shipped",
  "maxAttempts": 5,
  "rateLimitPerMinute": 60,
  "active": true
}
```

人工重放：

```http
POST http://localhost:8080/api/deliveries/1/retry
X-App-Id: platform-admin
X-Api-Key: admin-key
```

批量重放死信：

```http
POST http://localhost:8080/api/deliveries/dead-letter/replay
X-App-Id: platform-admin
X-Api-Key: admin-key
```

## 设计边界

当前版本是单体多模块，平台内部已经按可拆分边界建模：

- `EventRecord`：平台收到的业务事件
- `WebhookEndpoint`：订阅端点和投递配置
- `DeliveryTask`：可调度、可重试、可死信的投递任务
- `DeliveryAttempt`：每次 HTTP 投递的审计日志
- `ApplicationClient`：调用平台的业务应用身份和角色

关键扩展接口：

- `DeliveryQueue`：替换为 RabbitMQ/Kafka 时的队列端口
- `RateLimiter`：替换为 Redis 分布式限流时的限流端口

以后要扩展时，不需要重写项目：

- H2 替换为 MySQL/PostgreSQL
- Redis Lua 限流：已有 `RateLimiter` 端口，接 Redis 后替换实现
- RabbitMQ/Kafka 延迟队列：已有 `DeliveryQueue` 端口，接 MQ 后替换数据库队列实现
- 分片调度或 ShedLock：当前已有任务抢占字段，可继续强化为数据库锁或分片锁
- Prometheus/Grafana 面板：当前已暴露 Prometheus 指标
- 失败原因聚合、端点健康评分：可基于 `DeliveryAttempt` 继续统计
- 多语言 SDK：当前已有 JS SDK 示例，可继续补 Java/Go SDK

## 面试表达

推荐说法：

> 我实现了一个通用的可靠事件投递平台，业务系统可以通过 API 提交事件，平台会把事件异步投递到多个订阅端点，并支持签名、幂等、失败重试、死信、人工重放、投递日志和基础限流。我另外做了一个很小的订单系统和模拟接收端，用来验证真实业务链路和异常场景。

不推荐说法：

> 我做了一个商城，然后加了 Webhook。
