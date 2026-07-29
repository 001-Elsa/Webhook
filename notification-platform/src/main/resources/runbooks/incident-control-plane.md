# EventRelay incident guidance

Treat MySQL as the delivery state source of truth. A successful HTTP call may be
repeated when the worker crashes before committing; receivers must deduplicate
by Delivery ID.

## Auth and configuration failures

Never replay a 401, 403, or 404 until endpoint URL, API credentials, and HMAC
secret configuration are corrected. Signature verification and tenant isolation
must remain enabled while investigating auth errors.

## Rate limiting and Retry-After

Honor Retry-After for HTTP 429 responses. Reduce endpoint concurrency or ingress
quota before approving a ReplayJob so the receiver is not immediately re-limited.

## Receiver 5xx and transient network

For receiver 5xx or transient network failures, wait for recovery, preview with a
Dry Run, then use an approved ReplayJob. Do not disable TLS verification or SSRF
validation as a workaround.

## Outbox backlog and publisher stalls

When outbox backlog is elevated, inspect publisher health, RabbitMQ confirm
latency, and MySQL lease holders. MySQL remains authoritative; rebuild missing
queue work from Delivery state after broker recovery.

## Circuit open and endpoint pause

When a circuit is open or an endpoint is paused, resolve receiver health first,
then clear cooldown or resume the endpoint before replaying DEAD deliveries.
Never force traffic through an open circuit without an explicit operator action.
