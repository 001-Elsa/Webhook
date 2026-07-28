# Automated reliability matrix

| Fault window | Injection | Expected invariant | Automated evidence |
|---|---|---|---|
| RabbitMQ unavailable before confirm | Publisher queue throws / broker restart | Outbox remains `PENDING`; publish attempt backs off | `OutboxPublisherTest`, `InfrastructureIntegrationTest` |
| Confirm succeeds, DB completion not committed | Redeliver same claimed Outbox after lease expiry | Duplicate is allowed; Delivery ID is stable; no loss | stable message ID + terminal-state idempotency tests |
| HTTP succeeds before delivery commit | Redeliver delivery message | receiver deduplicates by `X-Webhook-Delivery-Id`; worker skips terminal task | receiver mock + `DeliveryServiceReliabilityTest` |
| MySQL unavailable during consume | throw `DataAccessException` | manual NACK with requeue; never false ACK | `RabbitQueueReliabilityTest` |
| Redis unavailable | stop Redis / throw `DataAccessException` | rate/idempotency cache fails open, MySQL remains authoritative, degraded metric emitted | `RedisReliabilityTest`, container restart drill |
| two publishers claim same due batch | concurrent `FOR UPDATE SKIP LOCKED` | disjoint leased batches | `OutboxBatchStoreIntegrationTest` |
| duplicate worker messages | optimistic state + lease claim | one worker owns non-terminal transition | `InfrastructureIntegrationTest` |
| infrastructure restart | restart MySQL, Redis, RabbitMQ and every role | readiness recovers and durable state remains | `scripts/fault-drill.ps1` JSON evidence |

Run `mvn verify` for deterministic evidence and `scripts/fault-drill.ps1` against
the Compose environment for a timestamped recovery report. The report records
injection time, recovery time and outcome; correlate it with database snapshots,
RabbitMQ queue depth and traces before declaring a drill successful.
