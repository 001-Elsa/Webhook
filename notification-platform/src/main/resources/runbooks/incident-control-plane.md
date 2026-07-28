# EventRelay incident guidance

Treat MySQL as the delivery state source of truth. A successful HTTP call may be
repeated when the worker crashes before committing; receivers must deduplicate
by Delivery ID. Never replay a 401/403/404 until configuration is corrected.
Honor Retry-After for 429. For receiver 5xx or transient network failures, wait
for recovery, preview with a Dry Run, then use an approved ReplayJob. Never
disable TLS verification, SSRF validation, tenant isolation, or signature
verification as a workaround.
