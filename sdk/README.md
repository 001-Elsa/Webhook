# Signature verification examples + ops CLI companion

**Version:** `0.1.0` (see `sdk/VERSION`)

This directory contains **signature verification examples** for receivers that
consume EventRelay webhooks. It is **not** a published Maven / PyPI / npm
package. Copy the language file you need into your receiver service.

| Language | Path |
|---|---|
| Java | `sdk/java/EventRelaySignatureVerifier.java` |
| Python | `sdk/python/eventrelay_signature.py` |
| JavaScript | `sdk/javascript/eventrelay-signature.js` |

## Verify contract

Examples verify the exact raw request body, timestamp tolerance, and `v1` HMAC
using constant-time comparison. Deduplicate successful requests by
`X-Webhook-Delivery-Id`; do not deduplicate only by event type or payload hash.
Never parse and reserialize JSON before verification.

## Subscription filter DSL (platform, not SDK)

Endpoint filter expressions (`$.field==value`, numbers/booleans, `&&`) are a
**deterministic filter DSL** validated at endpoint create time. This is **not**
a Schema Registry and does not execute scripts.

## Ops CLI

Operational queries live in `cli/` (`deliveries`, `replay`, `diagnose`) — see
`cli/README.md`. The CLI is a thin HTTP wrapper over the control-plane API Key
auth model, not part of a published SDK release.
