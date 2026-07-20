package com.example.webhook.platform.service;

import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class SignatureService {
    public String sign(String secret, Instant timestamp, String eventId, String payload) {
        String base = timestamp.toEpochMilli() + "." + eventId + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "t=" + timestamp.toEpochMilli() + ",v1=" + HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create webhook signature", ex);
        }
    }

    public boolean verify(String secret, String timestamp, String eventId, String payload, String signatureHeader) {
        String expected = sign(secret, Instant.ofEpochMilli(Long.parseLong(timestamp)), eventId, payload);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8));
    }
}
