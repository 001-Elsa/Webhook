# EventRelay 工程指南

## 系统边界

EventRelay 提供至少一次、可审计的事件投递。MySQL 是状态真相，RabbitMQ
是可重建的工作队列，Redis 是允许降级的缓存/限流依赖。接收方必须按
Delivery ID 幂等。系统不声称 exactly-once，也不把 AI 放进数据热路径。

## 数据面状态流

```text
POST /api/events
  -> Event + Delivery + Outbox（单事务）
  -> Publisher 批量 SKIP LOCKED 抢占
  -> 单消息 Rabbit confirm
  -> Worker 租约 + Tenant/Endpoint Bulkhead
  -> HTTP + HMAC
  -> SUCCEEDED / RETRYING(next_attempt_at) / DEAD
  -> Scheduler 到期创建幂等 Recovery Outbox
```

最危险的宕机窗口已经明确：

- confirm 后、Outbox 完成前：重复但不丢；
- HTTP 成功后、状态提交前：重复但不丢；
- MySQL 不可用：Consumer NACK/requeue；
- Redis 不可用：限流与幂等缓存 fail-open，MySQL 约束继续兜底并告警。

## 控制面

- API Key 生命周期：创建、双 Key 重叠、Scope、过期、撤销、使用审计；
- Secret 生命周期：版本化密钥环、新写使用 active version、后台重加密、
  移除旧 Key 前检查剩余版本计数；
- ReplayJob：Dry Run → 审批 → 分页重放 → 进度/取消 → 完整审计；
- Endpoint：暂停、恢复、并发、限流、熔断、订阅类型和确定性字段过滤；
- Incident Copilot：只读、脱敏、证据约束、离线评测、规则回退。

## 发布顺序

Flyway 迁移必须向后兼容上一版本。推荐顺序：

1. 执行迁移 Job；
2. 滚动 Scheduler 和 Publisher；
3. 滚动 Worker，等待在途 HTTP 与 ACK；
4. 滚动 API；
5. 执行 Smoke Test；
6. 失败时 Helm `--atomic` 回滚应用镜像，不自动回滚数据库。

## 变更验收

每次影响可靠性语义的变更至少需要：

- 状态机单元测试；
- Rabbit ACK/NACK 与 confirm 测试；
- MySQL/RabbitMQ/Redis Testcontainers；
- 对应故障矩阵行；
- 指标与告警；
- 正向、重复消息、依赖失败、恢复后的证据；
- 若影响容量，按四链路框架跑三轮对照，禁止凭配置推测 TPS。
