import { createHmac, timingSafeEqual } from 'node:crypto';

export function verifyEventRelaySignature({
  secret, timestampMs, eventId, rawBody, signatureHeader, toleranceSeconds = 300,
}) {
  const timestamp = Number(timestampMs);
  if (!Number.isFinite(timestamp)
      || Math.abs(Date.now() - timestamp) > toleranceSeconds * 1000) return false;
  const parts = Object.fromEntries(signatureHeader.split(',').map((part) => part.split('=', 2)));
  const expected = createHmac('sha256', secret)
    .update(`${timestampMs}.${eventId}.`).update(rawBody).digest('hex');
  const supplied = parts.v1 || '';
  if (expected.length !== supplied.length) return false;
  return timingSafeEqual(Buffer.from(expected), Buffer.from(supplied));
}
