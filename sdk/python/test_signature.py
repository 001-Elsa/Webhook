"""Minimal tests for EventRelay signature verification example."""
import hashlib
import hmac
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eventrelay_signature import verify


class SignatureTests(unittest.TestCase):
    def test_accepts_valid_signature(self):
        secret = "receiver-secret"
        event_id = "evt-1"
        payload = b'{"ok":true}'
        timestamp_ms = str(int(time.time() * 1000))
        signed = timestamp_ms.encode() + b"." + event_id.encode() + b"." + payload
        digest = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        header = f"t={timestamp_ms},v1={digest}"
        self.assertTrue(verify(secret, timestamp_ms, event_id, payload, header))

    def test_rejects_tampered_body(self):
        secret = "receiver-secret"
        event_id = "evt-1"
        payload = b'{"ok":true}'
        timestamp_ms = str(int(time.time() * 1000))
        signed = timestamp_ms.encode() + b"." + event_id.encode() + b"." + payload
        digest = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        header = f"t={timestamp_ms},v1={digest}"
        self.assertFalse(verify(secret, timestamp_ms, event_id, b'{"ok":false}', header))


if __name__ == "__main__":
    unittest.main()
