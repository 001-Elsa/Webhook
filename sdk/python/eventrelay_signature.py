"""Dependency-free EventRelay HMAC verifier."""
import hashlib
import hmac
import time


def verify(secret: str, timestamp_ms: str, event_id: str, payload: bytes,
           signature_header: str, tolerance_seconds: int = 300) -> bool:
    try:
        timestamp = int(timestamp_ms)
        if abs(int(time.time() * 1000) - timestamp) > tolerance_seconds * 1000:
            return False
        supplied = dict(part.split("=", 1) for part in signature_header.split(","))
        signed = timestamp_ms.encode() + b"." + event_id.encode() + b"." + payload
        expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, supplied.get("v1", ""))
    except (ValueError, KeyError):
        return False
