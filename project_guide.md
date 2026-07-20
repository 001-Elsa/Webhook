# Project Guide: Reliable Event Notification Platform

这份文档用来解释这个项目到底在做什么、三个模块之间怎么协作、代码应该从哪里读起。项目代码没有被修改，只新增了这份说明文档。

## 1. 这个项目一句话说明

这个项目不是一个商城项目，而是一个“可靠 Webhook 事件通知平台”的后端演示项目。

它模拟了一个常见业务场景：

1. 订单系统发生了业务事件，比如创建订单、支付订单、取消订单。
2. 订单系统不直接通知商户，而是把事件提交给一个独立的通知平台。
3. 通知平台负责保存事件、匹配订阅端点、异步投递、失败重试、签名、防重复、限流、记录投递日志。
4. 模拟接收端假装是商户服务器，用来验证 Webhook 是否真的被投递成功。

所以，这个项目真正想展示的是：如何设计一个可靠的事件投递系统，而不是如何写订单业务。

## 2. 项目整体结构

根目录是一个 Maven 多模块工程：

```text
D:\WebHook
├── pom.xml
├── notification-platform
├── demo-order-service
├── receiver-mock
├── scripts
├── data
└── logs
```

顶层 `pom.xml` 声明了三个子模块：

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `notification-platform` | `8080` | 核心 Webhook 通知平台 |
| `demo-order-service` | `8081` | 演示用订单服务，只负责产生订单事件 |
| `receiver-mock` | `8082` | 演示用 Webhook 接收端，模拟商户服务器 |

三个服务都是 Spring Boot 应用，Java 版本是 17，Spring Boot 版本是 3.3.5。

## 3. 三个服务分别做什么

### 3.1 notification-platform：核心通知平台

路径：

```text
notification-platform
```

这是整个项目的核心。它提供 API 给业务系统提交事件，也负责把事件投递到订阅者配置的 Webhook 地址。

它做的事情包括：

- 接收业务事件：`POST /api/events`
- 保存事件到数据库：`EventRecord`
- 根据事件类型匹配 Webhook 端点：`WebhookEndpoint`
- 为每个匹配端点生成投递任务：`DeliveryTask`
- 定时扫描需要投递的任务
- 使用 HTTP POST 把事件发送到接收方
- 给 Webhook 请求加 HMAC-SHA256 签名
- 记录每一次投递尝试：`DeliveryAttempt`
- 失败后指数退避重试
- 超过最大重试次数后进入死信状态：`DEAD`
- 支持手动重试和批量重放死信任务
- 提供简单管理页面和统计接口
- 暴露 Actuator 和 Prometheus 指标

可以把它理解成项目里的“中台”或“基础设施服务”。

### 3.2 demo-order-service：订单事件生产者

路径：

```text
demo-order-service
```

这个模块不是核心，只是为了演示“业务系统如何使用通知平台”。

它维护了一个内存里的订单 Map，没有数据库。用户可以创建订单、支付订单、取消订单、发货。每当订单状态变化时，它就调用通知平台：

```http
POST http://localhost:8080/api/events
```

它会带上认证请求头：

```http
X-App-Id: demo-order-service
X-Api-Key: order-key
```

事件类型大概是：

- `order.created`
- `order.paid`
- `order.cancelled`
- `order.shipped`

也就是说，订单服务只负责“发生了什么”，不负责“通知谁、失败怎么办、要不要重试”。

### 3.3 receiver-mock：模拟商户接收端

路径：

```text
receiver-mock
```

这个模块模拟外部商户的 Webhook 接收服务器。

它暴露：

```http
POST /webhook/{merchant}
```

通知平台会把事件 POST 到这里。接收端会校验请求签名，签名正确才认为请求可信。

它还有一个很适合演示失败重试的设计：默认会让下一次请求失败。这样你创建订单或支付订单后，会看到通知平台第一次投递失败，稍后自动重试成功。

## 4. 完整业务链路

以“支付订单”为例，完整流程如下：

```text
用户在订单服务页面点击支付
        ↓
demo-order-service 修改订单状态为 PAID
        ↓
OrderService.publish() 提交 order.paid 事件到 notification-platform
        ↓
notification-platform 的 EventController 接收请求
        ↓
ApiAuthFilter 校验 X-App-Id 和 X-Api-Key
        ↓
EventService 保存 EventRecord
        ↓
EventService 查找支持 order.paid 的 WebhookEndpoint
        ↓
为每个端点创建 DeliveryTask
        ↓
DeliveryService 定时扫描 PENDING / RETRYING 且到期的任务
        ↓
DeliveryService 抢占任务，防止多实例重复投递
        ↓
RateLimiter 检查端点限流
        ↓
SignatureService 生成 HMAC-SHA256 签名
        ↓
RestClient POST 到 receiver-mock 的 /webhook/demo-merchant
        ↓
receiver-mock 校验签名
        ↓
成功：DeliveryTask 变成 SUCCEEDED
失败：DeliveryTask 变成 RETRYING 或 DEAD，并记录 DeliveryAttempt
```

这个链路体现了 Webhook 平台的核心价值：业务系统只提交事件，平台负责可靠投递。

## 5. notification-platform 核心代码导读

### 5.1 启动类

```text
notification-platform/src/main/java/com/example/webhook/platform/NotificationPlatformApplication.java
```

Spring Boot 启动入口。平台服务运行在 `8080` 端口。

配置文件：

```text
notification-platform/src/main/resources/application.yml
```

里面配置了：

- 服务端口：`8080`
- H2 文件数据库：`./data/notification-platform`
- H2 控制台：`/h2-console`
- JPA 自动更新表结构：`ddl-auto: update`
- Actuator 暴露：`health/info/metrics/prometheus`
- Swagger UI：`/swagger-ui.html`
- 投递调度间隔：`webhook.dispatcher.fixed-delay-ms: 2000`

### 5.2 鉴权与请求上下文

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/security/ApiAuthFilter.java
notification-platform/src/main/java/com/example/webhook/platform/security/RequestContext.java
notification-platform/src/main/java/com/example/webhook/platform/security/ApiPrincipal.java
```

`ApiAuthFilter` 是平台 API 的认证过滤器。

写接口需要带：

```http
X-App-Id: xxx
X-Api-Key: xxx
```

启动时会自动创建两个演示客户端：

| appId | apiKey | role | 用途 |
| --- | --- | --- | --- |
| `platform-admin` | `admin-key` | `ADMIN` | 管理端点、重试任务等 |
| `demo-order-service` | `order-key` | `PRODUCER` | 提交事件 |

角色含义：

- `ADMIN`：基本什么写操作都能做
- `PRODUCER`：可以提交事件
- `VIEWER`：主要是只读

过滤器还会处理 `X-Trace-Id`。如果请求没传，就生成一个 UUID，并写进日志 MDC，方便链路追踪。

### 5.3 事件接收

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/api/EventController.java
notification-platform/src/main/java/com/example/webhook/platform/service/EventService.java
notification-platform/src/main/java/com/example/webhook/platform/api/dto/SubmitEventRequest.java
notification-platform/src/main/java/com/example/webhook/platform/api/dto/EventSubmitResponse.java
```

事件提交入口：

```http
POST /api/events
```

`EventController` 很薄，只是把请求交给 `EventService.submit()`。

`EventService.submit()` 做了几件关键事：

1. 如果请求里没有 `eventId`，就自动生成一个 UUID。
2. 根据 `tenantId + eventId` 检查是否重复事件。
3. 如果是重复事件，不再重复创建投递任务，直接返回 `duplicate: true`。
4. 保存 `EventRecord`。
5. 查找当前租户下 active 的 Webhook 端点。
6. 用 `EndpointMatcher` 判断端点是否订阅了该事件类型。
7. 为每个匹配端点创建一个 `DeliveryTask`。
8. 调用 `DeliveryQueue.enqueue()`。

当前的 `DeliveryQueue` 是数据库队列实现，`enqueue()` 里面没有真正发消息，因为调度器会定时扫描数据库任务。

### 5.4 端点管理

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/api/EndpointController.java
notification-platform/src/main/java/com/example/webhook/platform/domain/WebhookEndpoint.java
notification-platform/src/main/java/com/example/webhook/platform/service/EndpointMatcher.java
```

Webhook 端点就是“平台要把事件发到哪里”。

创建端点：

```http
POST /api/endpoints
```

端点字段主要包括：

| 字段 | 含义 |
| --- | --- |
| `tenantId` | 租户 ID |
| `name` | 端点名称 |
| `url` | 接收方 Webhook URL |
| `secret` | 签名密钥 |
| `eventTypes` | 订阅的事件类型，逗号分隔，`*` 表示全部 |
| `active` | 是否启用 |
| `maxAttempts` | 最大投递次数 |
| `rateLimitPerMinute` | 每分钟限流 |

`EndpointMatcher` 的逻辑很简单：

- 如果 `eventTypes` 是空或者 `*`，表示订阅所有事件。
- 否则按逗号拆分，精确匹配事件类型。

### 5.5 投递任务与调度

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/service/DeliveryService.java
notification-platform/src/main/java/com/example/webhook/platform/domain/DeliveryTask.java
notification-platform/src/main/java/com/example/webhook/platform/domain/DeliveryAttempt.java
notification-platform/src/main/java/com/example/webhook/platform/repo/DeliveryTaskRepository.java
```

这是平台最核心的部分。

`DeliveryTask` 表示“一个事件要投递到一个端点”的任务。

一个事件可能匹配多个端点，所以一个 `EventRecord` 可以对应多个 `DeliveryTask`。

`DeliveryService.dispatchDueTasks()` 有 `@Scheduled` 注解，每隔 2 秒运行一次。它会扫描：

- 状态是 `PENDING` 或 `RETRYING`
- `nextAttemptAt <= now`

的任务，每次最多取 20 个。

为了支持以后多实例部署，它不是直接投递，而是先抢占任务：

```text
lockedBy
lockedUntil
lockVersion
```

抢占成功后，当前 worker 才会执行投递。这样可以降低多个平台实例同时投递同一个任务的风险。

### 5.6 HTTP 投递与签名

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/service/DeliveryService.java
notification-platform/src/main/java/com/example/webhook/platform/service/SignatureService.java
```

投递时，平台会向端点 URL 发送 JSON 请求，并带上这些 Header：

```http
X-Webhook-Event-Id
X-Webhook-Event-Type
X-Webhook-Delivery-Id
X-Webhook-Timestamp
X-Webhook-Signature
X-Trace-Id
```

签名算法是 HMAC-SHA256。

签名原文格式：

```text
timestamp.eventId.payload
```

签名 Header 格式：

```text
t=时间戳,v1=签名摘要
```

接收方可以用同一个 secret 重新计算签名，如果一致，就说明请求确实来自平台，而且内容没有被篡改。

### 5.7 失败重试与死信

投递成功时：

```text
DeliveryTask.status = SUCCEEDED
```

投递失败时，平台会进入 `markFailure()`：

```text
notification-platform/src/main/java/com/example/webhook/platform/service/DeliveryService.java
```

逻辑是：

1. 记录失败原因到 `DeliveryAttempt`。
2. 更新 `DeliveryTask.lastError`。
3. 如果当前尝试次数已经达到端点的 `maxAttempts`，任务进入 `DEAD`。
4. 否则进入 `RETRYING`，并设置下一次重试时间。

重试延迟是指数退避：

```text
2^attemptNo 秒，最大不超过 300 秒
```

例如：

- 第 1 次失败后，约 2 秒后重试
- 第 2 次失败后，约 4 秒后重试
- 第 3 次失败后，约 8 秒后重试

死信任务可以通过接口查看和重放：

```http
GET  /api/deliveries/dead-letter
POST /api/deliveries/dead-letter/replay
```

单个任务也可以手动重试：

```http
POST /api/deliveries/{id}/retry
```

### 5.8 投递日志

每次 HTTP 投递，不管成功还是失败，都会保存一条 `DeliveryAttempt`。

字段包括：

| 字段 | 含义 |
| --- | --- |
| `delivery` | 关联的投递任务 |
| `attemptNo` | 第几次尝试 |
| `success` | 是否成功 |
| `statusCode` | HTTP 状态码 |
| `responseBody` | 成功响应内容 |
| `errorMessage` | 失败原因 |
| `durationMs` | 耗时 |
| `createdAt` | 尝试时间 |

这就是平台的审计日志。真实生产环境里，排查“为什么商户没收到通知”时，这张表非常关键。

### 5.9 限流

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/service/RateLimiter.java
notification-platform/src/main/java/com/example/webhook/platform/service/InMemoryRateLimiter.java
notification-platform/src/main/java/com/example/webhook/platform/service/RedisRateLimiterDesign.java
```

当前实现是内存限流：每个端点维护最近一分钟的投递时间戳，超过 `rateLimitPerMinute` 就暂时不投递，5 秒后再试。

这个实现适合单机演示，不适合多实例生产环境。

项目里已经留了 `RateLimiter` 接口，并且有 `RedisRateLimiterDesign` 说明，意思是以后可以换成 Redis Lua 脚本做分布式限流。

### 5.10 数据库队列扩展点

关键文件：

```text
notification-platform/src/main/java/com/example/webhook/platform/queue/DeliveryQueue.java
notification-platform/src/main/java/com/example/webhook/platform/queue/DatabaseDeliveryQueue.java
```

当前的队列实现叫 `DatabaseDeliveryQueue`。

它本质上不是传统消息队列，而是：

1. 事件提交时创建 `DeliveryTask` 数据库记录。
2. 定时任务扫描到期的 `DeliveryTask`。
3. 执行 HTTP 投递。

这叫数据库队列模式。

优点：

- 简单
- 演示成本低
- 不需要额外部署 RabbitMQ、Kafka、Redis
- 任务和状态天然持久化

缺点：

- 高并发下数据库压力会变大
- 延迟和吞吐不如专业 MQ

代码通过 `DeliveryQueue` 接口做隔离，将来可以替换成：

- RabbitMQ
- Kafka
- Redis Stream
- 延迟队列

## 6. 主要数据模型

### 6.1 ApplicationClient

路径：

```text
notification-platform/src/main/java/com/example/webhook/platform/domain/ApplicationClient.java
```

表示调用平台 API 的应用身份。

它解决的问题是：谁可以调用平台？这个调用者有什么权限？

核心字段：

- `tenantId`
- `appId`
- `apiKey`
- `role`
- `active`

### 6.2 WebhookEndpoint

路径：

```text
notification-platform/src/main/java/com/example/webhook/platform/domain/WebhookEndpoint.java
```

表示一个订阅端点，也就是事件最终要投递到的地方。

例如默认演示端点：

```text
http://localhost:8082/webhook/demo-merchant
secret: demo-secret
eventTypes: order.created,order.paid,order.cancelled,order.shipped
```

### 6.3 EventRecord

路径：

```text
notification-platform/src/main/java/com/example/webhook/platform/domain/EventRecord.java
```

表示平台收到的一条业务事件。

比如：

```json
{
  "eventId": "order.paid:10001:uuid",
  "type": "order.paid",
  "data": {
    "orderId": "10001",
    "amount": 99.9,
    "status": "PAID"
  }
}
```

`eventId` 是幂等关键字段。平台用它判断同一个事件是否已经提交过。

### 6.4 DeliveryTask

路径：

```text
notification-platform/src/main/java/com/example/webhook/platform/domain/DeliveryTask.java
```

表示一次待投递任务。

注意：它不是事件本身，而是“把某个事件投递给某个端点”的任务。

如果一个事件匹配 3 个端点，就会生成 3 个 `DeliveryTask`。

状态包括：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 等待第一次投递 |
| `RETRYING` | 投递失败，等待重试 |
| `SUCCEEDED` | 投递成功 |
| `DEAD` | 超过最大重试次数，进入死信 |

### 6.5 DeliveryAttempt

路径：

```text
notification-platform/src/main/java/com/example/webhook/platform/domain/DeliveryAttempt.java
```

表示一次实际 HTTP 请求尝试。

`DeliveryTask` 和 `DeliveryAttempt` 的区别是：

- `DeliveryTask`：任务整体状态
- `DeliveryAttempt`：每次尝试的日志

例如一个任务失败两次、第三次成功，那么会有：

```text
1 个 DeliveryTask
3 个 DeliveryAttempt
```

## 7. demo-order-service 代码导读

关键文件：

```text
demo-order-service/src/main/java/com/example/webhook/order/OrderController.java
demo-order-service/src/main/java/com/example/webhook/order/OrderService.java
demo-order-service/src/main/java/com/example/webhook/order/OrderRecord.java
demo-order-service/src/main/java/com/example/webhook/order/OrderStatus.java
```

订单接口：

```http
GET  /api/orders
POST /api/orders
POST /api/orders/{orderId}/pay
POST /api/orders/{orderId}/cancel
POST /api/orders/{orderId}/ship
```

`OrderService` 用一个 `ConcurrentHashMap` 保存订单，不落数据库。

每次订单变化后都会调用：

```java
publish("order.xxx", order)
```

`publish()` 会请求通知平台：

```http
POST /api/events
X-App-Id: demo-order-service
X-Api-Key: order-key
X-Trace-Id: order-{orderId}
```

这体现了生产者服务的正确职责边界：只发事件，不管投递细节。

## 8. receiver-mock 代码导读

关键文件：

```text
receiver-mock/src/main/java/com/example/webhook/receiver/ReceiverController.java
receiver-mock/src/main/java/com/example/webhook/receiver/ReceivedWebhook.java
```

核心接口：

```http
POST /webhook/{merchant}
```

它会读取平台发来的 Header：

```http
X-Webhook-Event-Id
X-Webhook-Event-Type
X-Webhook-Delivery-Id
X-Webhook-Timestamp
X-Webhook-Signature
```

然后用本地 `secret` 校验签名。

它还提供：

```http
GET  /api/received
GET  /api/config
POST /api/config
```

`failNext` 可以配置接下来失败几次。默认是 `1`，所以第一次 Webhook 会失败，便于观察平台的自动重试。

## 9. API 速查

### 9.1 提交事件

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

### 9.2 创建 Webhook 端点

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

### 9.3 查看投递任务

```http
GET http://localhost:8080/api/deliveries
```

GET 请求在这个项目里允许页面直接读取，通常不需要认证头。

### 9.4 查看投递尝试日志

```http
GET http://localhost:8080/api/deliveries/attempts
GET http://localhost:8080/api/deliveries/{id}/attempts
```

### 9.5 手动重试

```http
POST http://localhost:8080/api/deliveries/1/retry
X-App-Id: platform-admin
X-Api-Key: admin-key
```

### 9.6 查看和重放死信

```http
GET  http://localhost:8080/api/deliveries/dead-letter
POST http://localhost:8080/api/deliveries/dead-letter/replay
X-App-Id: platform-admin
X-Api-Key: admin-key
```

### 9.7 统计接口

```http
GET http://localhost:8080/api/dashboard/stats
```

返回端点数、事件数、不同状态的投递任务数量。

## 10. 页面入口

三个服务都有静态页面：

| 页面 | URL | 用途 |
| --- | --- | --- |
| 通知平台管理页 | `http://localhost:8080` | 看统计、事件、任务、投递日志、手动重试 |
| 订单服务页面 | `http://localhost:8081` | 创建订单、支付、取消、发货 |
| 模拟接收端页面 | `http://localhost:8082` | 配置失败次数、查看收到的 Webhook |

还有：

| 工具 | URL |
| --- | --- |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 Console | `http://localhost:8080/h2-console` |
| Actuator Health | `http://localhost:8080/actuator/health` |
| Prometheus 指标 | `http://localhost:8080/actuator/prometheus` |

## 11. 如何运行

项目提供了 PowerShell 脚本。

构建全部模块：

```powershell
.\scripts\build.ps1
```

分别启动三个服务：

```powershell
.\scripts\run-platform.ps1
.\scripts\run-order.ps1
.\scripts\run-receiver.ps1
```

建议开三个终端分别运行，因为它们是三个独立 Spring Boot 服务。

启动顺序建议：

1. `notification-platform`
2. `receiver-mock`
3. `demo-order-service`

演示流程：

1. 打开 `http://localhost:8082`，确认模拟接收端的 `failNext` 是 `1`。
2. 打开 `http://localhost:8081`，创建订单。
3. 点击支付。
4. 打开 `http://localhost:8080`。
5. 观察投递任务第一次失败，然后自动重试成功。

## 12. 这个项目的设计重点

### 12.1 业务系统与通知平台解耦

订单服务不直接关心商户地址，也不处理失败重试。它只是说：

```text
我发生了 order.paid 事件
```

通知平台再决定：

```text
谁订阅了这个事件？
要投递到哪里？
失败了怎么重试？
要记录哪些日志？
```

这就是事件平台的价值。

### 12.2 事件和投递任务分离

`EventRecord` 代表业务事实。

`DeliveryTask` 代表投递动作。

这样设计很重要，因为一个事件可能要发给多个接收方，每个接收方的成功失败状态也不同。

### 12.3 每次尝试都可追踪

`DeliveryAttempt` 让系统可以回答这些问题：

- 这个事件投递过几次？
- 每次是什么时间？
- HTTP 响应是什么？
- 失败原因是什么？
- 最后成功了吗？

这是可靠系统必备的可观测性。

### 12.4 幂等

`EventService` 会用 `tenantId + eventId` 判断重复提交。

如果业务系统因为网络问题重复提交同一个事件，平台不会重复生成投递任务。

不过要注意，实体上现在的唯一约束写的是 `eventId`，而服务层查的是 `tenantId + eventId`。演示环境没有问题，但如果未来真正多租户化，数据库唯一约束最好也调整成 `(tenantId, eventId)`。

### 12.5 失败重试和死信

Webhook 投递本质上是不可靠的：对方服务可能挂了、网络可能超时、对方返回 500。

这个项目没有假设一次就成功，而是把失败作为正常情况处理：

- 失败就记录日志
- 没超过最大次数就重试
- 超过最大次数就进死信
- 管理员可以手动重放

### 12.6 签名

Webhook 请求是跨系统调用，接收方必须知道请求是不是可信。

所以平台用 `secret` 对请求内容签名，接收方用同一个 `secret` 验签。

这可以防止别人伪造 Webhook 请求。

### 12.7 可替换的扩展点

项目已经把两个容易升级的地方抽成接口：

| 接口 | 当前实现 | 未来可以替换成 |
| --- | --- | --- |
| `DeliveryQueue` | 数据库扫描 | RabbitMQ / Kafka / Redis Stream |
| `RateLimiter` | 单机内存限流 | Redis 分布式限流 |

这说明作者有意识地把演示实现和生产级实现隔离开了。

## 13. 这个项目目前不是生产级的地方

这个项目适合展示设计思路和后端工程能力，但如果要上生产，还需要补强：

1. API Key 目前是明文存数据库，生产环境应存哈希值。
2. H2 适合演示，生产应换 MySQL、PostgreSQL 等。
3. 内存限流只适合单实例，生产多实例要换 Redis。
4. 数据库队列简单可靠，但高吞吐场景应考虑 MQ。
5. 接收方返回非 2xx 时，目前主要通过 `RestClientException` 处理，需要根据 Spring RestClient 行为进一步细分状态码记录。
6. Webhook 签名校验没有时间窗口校验，生产环境通常会拒绝太旧的 timestamp，防重放攻击。
7. 管理 API 的租户隔离还可以更严格，比如查询接口现在返回全部记录。
8. 缺少自动化测试。
9. 缺少真正的用户管理、密钥轮换、端点健康评分等能力。

这些不是缺陷本身，因为项目定位是演示版；但面试或继续开发时要知道边界在哪里。

## 14. 如果你要按顺序读代码

推荐阅读顺序：

1. `pom.xml`
2. `notification-platform/src/main/resources/application.yml`
3. `notification-platform/src/main/java/com/example/webhook/platform/config/DemoDataInitializer.java`
4. `demo-order-service/src/main/java/com/example/webhook/order/OrderService.java`
5. `notification-platform/src/main/java/com/example/webhook/platform/api/EventController.java`
6. `notification-platform/src/main/java/com/example/webhook/platform/service/EventService.java`
7. `notification-platform/src/main/java/com/example/webhook/platform/domain/EventRecord.java`
8. `notification-platform/src/main/java/com/example/webhook/platform/domain/WebhookEndpoint.java`
9. `notification-platform/src/main/java/com/example/webhook/platform/domain/DeliveryTask.java`
10. `notification-platform/src/main/java/com/example/webhook/platform/service/DeliveryService.java`
11. `notification-platform/src/main/java/com/example/webhook/platform/domain/DeliveryAttempt.java`
12. `receiver-mock/src/main/java/com/example/webhook/receiver/ReceiverController.java`
13. `notification-platform/src/main/java/com/example/webhook/platform/security/ApiAuthFilter.java`

按这个顺序读，会先理解“业务事件从哪里来”，再理解“平台怎么处理”，最后理解“安全和工程化细节”。

## 15. 面试时怎么介绍这个项目

可以这样说：

> 我实现了一个通用的可靠 Webhook 事件通知平台。业务系统通过 API 提交事件，平台会把事件持久化，然后根据订阅端点异步投递。投递过程中支持 HMAC 签名、幂等、失败重试、死信、人工重放、投递日志、基础限流、链路追踪和 Prometheus 指标。我还写了一个小型订单服务作为事件生产者，以及一个模拟商户接收端，用来验证真实的投递和失败重试链路。

不要把它说成：

> 我做了一个订单系统。

更准确的表达是：

> 订单系统只是演示用的事件生产者，项目核心是可靠事件投递平台。

## 16. 最后总结

这个项目的主线可以压缩成一句话：

```text
业务系统提交事件，通知平台可靠地把事件投递到订阅方，并负责失败重试、签名、日志和死信治理。
```

理解这个项目时，不要被三个服务吓到。它们的角色很清楚：

- `demo-order-service`：制造事件
- `notification-platform`：保存事件并可靠投递
- `receiver-mock`：接收事件并模拟失败

真正需要重点掌握的是 `notification-platform` 里的事件模型、投递任务模型、调度重试逻辑和签名机制。
