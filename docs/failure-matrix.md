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
| RabbitMQ outage | stop broker while accepting a real matching Event | Outbox stays `PENDING`, then drains to one terminal Delivery | `scripts/fault-drill.ps1` database/queue/Prometheus evidence |
| Publisher outage | stop publisher while accepting a real matching Event | durable Outbox backlog drains after role recovery | `scripts/fault-drill.ps1` database/queue/Prometheus evidence |
| Worker outage | stop worker after publication | RabbitMQ redelivers after worker recovery | `scripts/fault-drill.ps1` database/queue/Prometheus evidence |
| HTTP success / DB failure | delay receiver response, kill MySQL after receiver observes request | HTTP repeat is allowed; one Delivery reaches one terminal database state | `scripts/fault-drill.ps1` receiver + database evidence |
| Redis outage | stop Redis during a real matching Event | cache/rate-limit degradation does not make durable delivery unavailable | `scripts/fault-drill.ps1` terminal event evidence |

Run `mvn verify` for deterministic evidence and `scripts/fault-drill.ps1` against
the Compose environment for a timestamped recovery report. The script creates a
matching `fault.drill` Endpoint, records every Event ID, and writes JSON/Markdown
plus database, RabbitMQ and Prometheus snapshots to `docs/evidence/`; do not
declare a drill successful without its generated report.
