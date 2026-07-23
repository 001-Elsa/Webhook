# EventRelay 工程设计说明

本文面向代码评审和面试追问，运行命令见 `README.md`。

## 1. 目标与非目标

目标是提供多租户、可恢复、可审计的 Webhook 至少一次投递。系统保证业务事务提交后，投递意图最终可以被发现和发布；不承诺 exactly-once，也不保证跨事件严格顺序。

非目标：工作流编排、消息内容转换、全局事件顺序、接收端数据库事务与平台事务的一致提交。

## 2. 模块

| 模块 | 职责 |
| --- | --- |
| `notification-platform` | 认证、事件入库、Outbox、投递、重试、状态机、指标 |
| `demo-order-service` | 示例 Producer |
| `receiver-mock` | HMAC 校验、Delivery ID 幂等、可控失败 |
| `deploy` | Prometheus、告警规则、Grafana provisioning |

## 3. Transactional Outbox

事件提交事务同时写入：

1. `event_records`
2. 每个匹配 Endpoint 对应的 `delivery_tasks`
3. 对应的 `outbox_messages`

任一写入失败都会整体回滚。Outbox Publisher 使用条件更新抢占记录，等待 RabbitMQ publisher confirm，并检查 mandatory return。成功后标记 `PUBLISHED`；失败保留 `PENDING`，按指数退避重试。

确认后、Outbox 状态提交前宕机会重复发布。这是主动选择的 at-least-once 取舍：允许重复，不允许静默丢失。

## 4. Delivery 执行

Consumer 收到稳定的 Delivery ID 后：

1. 条件更新 `locked_by/locked_until` 抢占任务。
2. 已成功任务直接 `SKIPPED`，处理重复消息。
3. Redis Lua 限流；Redis 故障时 fail-open 并记录告警。
4. 解密 Endpoint secret，生成 HMAC-SHA256 签名。
5. 调用经过 DNS 级 SSRF 校验且禁止重定向的 HTTP Client。
6. 在数据库事务中写 DeliveryAttempt、更新 Delivery/Event，并写 RETRY/DEAD Outbox。
7. 数据库事务成功后 ACK Rabbit 消息；数据库故障则 NACK requeue。

HTTP 成功后、结果事务提交前宕机会重复调用接收端，因此接收端必须按 `X-Webhook-Delivery-Id` 幂等。

## 5. 状态机

Delivery 合法转换：

- `PENDING -> RETRYING | SUCCEEDED | DEAD`
- `RETRYING -> RETRYING | SUCCEEDED | DEAD`
- `DEAD -> RETRYING`，仅人工重放
- `SUCCEEDED` 不允许回退

Event 状态由其全部 Delivery 聚合。并发完成可能造成瞬时读视图不一致，因此定时 Reconciler 会重新计算仍处于 `DISPATCHING` 的 Event，提供最终收敛。

## 6. 安全边界

- Producer 只能 `POST /api/events`；Admin 才能查询、配置和重放。
- 所有业务查询使用 tenant 条件，控制器只返回 DTO。
- API Key 使用 PBKDF2-SHA256 加盐哈希。
- Webhook secret 使用 AES-256-GCM 加密；主密钥由外部 Secret 注入。
- URL 在保存时和真实 DNS 连接时校验；禁止私网、环回、link-local、multicast、IPv6 ULA 和 HTTP redirect。
- Trace ID、事件类型、ID、Endpoint 字段均有限长校验。
- Demo、数据库、Redis、RabbitMQ、Grafana 均无仓库内固定密码。

## 7. 故障与恢复

| 场景 | 处理 |
| --- | --- |
| RabbitMQ 暂停/重启 | Outbox backlog 增长，恢复后继续发布 |
| Redis 暂停/重启 | 限流 fail-open，恢复后继续使用 Redis |
| MySQL 暂停/重启 | Consumer NACK，连接池恢复后重试 |
| Worker 抢占后宕机 | Rabbit unacked 重投；锁到期后补偿扫描恢复 |
| 重复 Rabbit 消息 | 条件抢占与终态检查跳过 |
| 发布确认丢失 | Outbox 重发，可能重复但不丢失 |

Testcontainers 测试包含真实基础设施连接、业务数据与 Outbox 原子回滚、MySQL/Redis/RabbitMQ 容器重启恢复。无 Docker 时仅跳过这些集成测试，不阻塞单元测试。

## 8. 可观测性

日志使用 JSON 输出并携带 MDC traceId。Prometheus 指标覆盖：

- Delivery success/failure/duration/outcome/recovery
- Outbox pending/published/failure/deleted
- Auth failure
- Event reconciliation

告警覆盖实例宕机、Outbox 积压、发布失败、失败率和 P99。Grafana 同时展示吞吐、P95/P99 与 Outbox 健康度。

## 9. 性能验证纪律

`scripts/load-test.js` 使用 constant-arrival-rate，并对错误率、P95、P99 设置阈值。没有原始 k6 summary、机器配置和 Git commit，不允许在简历中写吞吐提升百分比。报告模板位于 `docs/performance-report.md`。

## 10. 进一步演进

真正生产环境可继续加入 KMS 信封加密与在线轮换、分区 Outbox、按 Endpoint 公平调度、SLO burn-rate 告警、OpenTelemetry trace、归档存储和跨区域灾备。这些属于下一阶段能力，不在当前实现中冒充已完成。
