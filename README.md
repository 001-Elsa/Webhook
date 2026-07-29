# EventRelay — 多租户可靠事件投递平台

EventRelay 是一个以 MySQL 为事实来源、提供至少一次投递语义的 Webhook
基础设施。项目重点不是堆叠中间件，而是把可靠性、水平扩容、租户治理、
安全边界、生产部署和故障验证做成可运行闭环。

## 可靠性语义

- Event、Delivery 与 Outbox 在同一个 MySQL 事务创建；
- Publisher 使用 `FOR UPDATE SKIP LOCKED` 批量抢占、有界并发和逐消息
  RabbitMQ correlated confirm + mandatory return；
- confirm 成功但 Outbox 状态未提交时允许重复，稳定 Delivery ID 保证接收方
  可以幂等，系统不承诺 exactly-once；
- Worker 手动 ACK；MySQL 不可用时 NACK/requeue，不能持久化终态时不做错误
  ACK；
- `next_attempt_at` 是唯一重试时钟，Scheduler 到期后创建幂等 Recovery
  Outbox，不依赖固定 TTL 重试队列；
- Delivery 使用租约与乐观锁；补偿扫描可以重建 RabbitMQ 中缺失的工作；
- DEAD 批量重放是异步 ReplayJob，支持 Dry Run、最大数量、审批、进度和取消。

完整故障不变量见 [failure-matrix.md](docs/failure-matrix.md)。

## 多租户与安全

- 所有控制面查询按 tenant 强制过滤；
- 租户配额覆盖每秒接入（令牌桶）、每日事件、待投递数量、投递并发和
  每日 Payload 接入流量（非磁盘存储占用）；
- 恢复扫描与 Outbox 抢占按租户公平分批并支持调度权重；Worker 使用
  Tenant + Endpoint 本地 Bulkhead，可选 Redis 集群级租户并发上限；
- Endpoint 具有独立限流、并发上限、CLOSED/OPEN/HALF_OPEN 共享熔断、
  暂停/恢复；
- API Key 使用 PBKDF2 哈希，支持 Key ID、多 Key 并存、Scope、过期、撤销、
  最后使用时间/IP 和双 Key 平滑轮换；Legacy Key 可通过独立开关与截止时间下线；
- Webhook Secret 使用 AES-256-GCM；默认 Local KEK + envelope DEK，
  可选 Vault Transit / AWS KMS 适配器；旧密文可继续解密并后台重加密；
- 出站 URL 有 SSRF 校验，Webhook 使用 HMAC 签名；
- 管理操作、ReplayJob 和 AI 诊断均保存租户级审计记录。

## 独立运行角色

代码仍在同一个 Spring Boot 模块中，但部署单元已经分离：

| Profile | 职责 | 扩容依据 |
|---|---|---|
| `api` | 鉴权、事件接入、控制面 API | 请求率、API 延迟 |
| `publisher` | Outbox 抢占与 RabbitMQ confirm | backlog、最老 Outbox 年龄 |
| `worker` | RabbitMQ 消费与 HTTP 投递 | ready 数、Delivery 等待时间 |
| `scheduler` | 到期重试、对账、归档、ReplayJob、密钥轮换 | 单实例，数据库租约选主 |

非 API 角色保留独立 management 端口，但 `/api` 返回 404。Spring graceful
shutdown、Rabbit listener shutdown timeout、Publisher drain 和 Kubernetes
`preStop` 共同保证滚动发布时停止领取新工作并等待在途任务。

## 可观测性与 SLO

Micrometer、Prometheus、Grafana、OpenTelemetry Collector 与 Jaeger 覆盖
API → MySQL → Outbox → RabbitMQ → Worker → Webhook HTTP。消息 observation
会传播 trace 上下文。关键指标包括：

- Outbox 数量、最老年龄、批量大小、confirm 延迟、发布线程/队列；
- Delivery 领取等待、HTTP 总耗时、端到端终态延迟、ready/DEAD；
- Hikari、JVM、GC、进程资源和 Redis 降级；
- ReplayJob、配额拒绝、熔断、密钥重加密和诊断分类。

Prometheus 规则包含 99.9% 成功率 SLO 的快/慢多窗口 Burn Rate 告警。

## 可选 AI 控制面

`POST /api/deliveries/{id}/diagnosis` 是只读 Incident Copilot。默认使用可测试
的确定性规则；配置 `WEBHOOK_AI_ENABLED=true` 后可调用受控模型代理。输入
不包含 Payload、Secret、API Key、完整响应体或原始栈，只包含脱敏错误、
状态码、尝试时间线和积压信号。模型输出必须引用有效 evidence ID，否则
丢弃并回退规则。AI 不能 ACK、重试、修改 Payload 或执行重放，失败不会
影响投递链路。Runbook 采用结构化关键词/章节检索注入上下文（不是向量
RAG）。12 类离线用例评估的是规则分类器映射，不是大模型准确率。

## 本地启动

复制 `.env.example`，设置随机凭证和 32 字节 Base64 加密密钥，然后：

```powershell
docker compose up -d --build --wait
```

主要入口：

- API：<http://localhost:8080>
- Receiver Mock：<http://localhost:8082>
- RabbitMQ：<http://localhost:15672>
- Prometheus：<http://localhost:9090>
- Grafana：<http://localhost:3000>
- Jaeger：<http://localhost:16686>

验证：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd verify
docker compose config -q
```

## 性能、部署与灾备

- [performance-report.md](docs/performance-report.md) 明确区分接入、Outbox、
  Worker 和真实端到端链路；每档三次并保留 Prometheus/MySQL/容器原始证据。
- [Helm Chart](deploy/helm/eventrelay) 包含四角色 Deployment、ConfigMap、
  Secret 引用、startup/readiness/liveness、PDB、HPA、requests/limits、
  NetworkPolicy、迁移 Job、滚动更新与原子回滚；验收清单见
  [helm-acceptance.md](docs/helm-acceptance.md)。
- CI（`.github/workflows/ci.yml`）阶段包括：`mvn verify`（单元/Testcontainers）、
  Compose 校验、CycloneDX SBOM、Gitleaks Secret 扫描、不可变 SHA 镜像构建、
  Trivy 门禁、以及测试环境四角色部署与端到端 Smoke。流水线是否当前全绿取决于
 最新提交结果，本文不宣称 CI 恒绿。
- [disaster-recovery.md](docs/disaster-recovery.md) 定义备份、恢复顺序以及
  设计目标 RPO/RTO；实测演练证据使用 `docs/evidence/*-TEMPLATE.md`。
- 控制面认证以 API Key 为主；OIDC/OAuth2/mTLS 见
  [auth-roadmap.md](docs/auth-roadmap.md)。

## 容量声明

历史上已复现的结果仅为单实例、无匹配 Endpoint 的 50 TPS 接入基线：
P95 220.44 ms、P99 300.48 ms、成功率 100%。历史 200 TPS 实验只达到
66.21 TPS，并出现明显排队，不能写成现有能力。新的批量 Publisher、角色
分离和多实例结构已经具备，但任何新 TPS、端到端延迟或水平扩展数字都必须
在 `scripts/performance/run-suite.ps1` 实测三轮通过后才能用于简历。

## SDK 与运维工具

`sdk/` 提供 Java、Python、JavaScript 的常量时间签名验证示例（版本 0.1.0，
未发布到 Maven/PyPI/npm）；`cli/` 支持查询 Delivery、创建 ReplayJob 和
只读诊断。事件支持 `schemaVersion`，Endpoint 支持受限的确定性订阅过滤
DSL（字符串/数字/布尔与 `&&`），这不是 Schema Registry。

## 安全门禁

CI 保留 Gitleaks、CycloneDX SBOM、不可变镜像扫描、SARIF 上传和 Cosign 签名。
Trivy 的应用依赖库扫描会使 **可修复的 CRITICAL** 漏洞直接阻断 CI。未修复问题和
基础镜像问题仍会出现在 SARIF 中，但不会被笼统地豁免。任何临时例外都必须在
[`.trivyignore`](.trivyignore) 中精确列出一个 CVE，并在对应 Pull Request 记录到期
时间和审查理由。验收时可在分支中引入一个有已知可修复 CRITICAL 公告的依赖版本：
`Trivy gate: fixable CRITICAL application libraries` 必须失败；升级或移除该依赖后
必须恢复为绿色。
