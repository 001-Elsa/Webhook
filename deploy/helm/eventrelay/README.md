# EventRelay Helm deployment

Create `eventrelay-secrets` with `MYSQL_USER`, `MYSQL_PASSWORD`,
`RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `REDIS_PASSWORD`, and either
`WEBHOOK_ENCRYPTION_KEY` or a versioned `WEBHOOK_ENCRYPTION_KEYS` key ring.

Install or upgrade:

```bash
helm upgrade --install eventrelay deploy/helm/eventrelay \
  --set image.repository=registry.example/eventrelay \
  --set image.tag=$GIT_SHA --atomic --timeout 10m
```

`--atomic` rolls back a failed install/upgrade. For a manual rollback:

```bash
helm history eventrelay
helm rollback eventrelay <REVISION> --wait
```

Database migrations must remain backward compatible with the previously
deployed application version; schema rollback is deliberately not automatic.
