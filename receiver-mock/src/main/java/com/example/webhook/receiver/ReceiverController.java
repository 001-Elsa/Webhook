package com.example.webhook.receiver;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;

@RestController
public class ReceiverController {
    private final List<ReceivedWebhook> received = new ArrayList<>();
    private final AtomicInteger failNext = new AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicLong responseDelayMs = new java.util.concurrent.atomic.AtomicLong();
    private final Set<String> processedDeliveries = ConcurrentHashMap.newKeySet();
    private final String secret;

    public ReceiverController(@Value("${receiver.webhook-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("RECEIVER_WEBHOOK_SECRET is required");
        }
        this.secret = secret;
    }

    @PostMapping("/webhook/{merchant}")
    public Map<String, Object> receive(@PathVariable String merchant,
                                       @RequestHeader("X-Webhook-Event-Id") String eventId,
                                       @RequestHeader("X-Webhook-Event-Type") String eventType,
                                       @RequestHeader("X-Webhook-Delivery-Id") String deliveryId,
                                       @RequestHeader("X-Webhook-Timestamp") String timestamp,
                                       @RequestHeader("X-Webhook-Signature") String signature,
                                       @RequestBody String payload) {
        boolean valid = verify(timestamp, eventId, payload, signature);
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        if (!processedDeliveries.add(deliveryId)) {
            return Map.of("received", true, "duplicate", true, "eventId", eventId, "deliveryId", deliveryId);
        }
        if (failNext.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            processedDeliveries.remove(deliveryId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Configured demo failure");
        }
        synchronized (received) {
            received.add(0, new ReceivedWebhook(Instant.now(), merchant, eventId, eventType, deliveryId, true, payload));
            if (received.size() > 100) {
                received.remove(received.size() - 1);
            }
        }
        delayResponse();
        return Map.of("received", true, "eventId", eventId, "deliveryId", deliveryId);
    }

    @GetMapping("/api/received")
    public List<ReceivedWebhook> list() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    @PostMapping("/api/config")
    public Map<String, Object> config(@RequestBody ReceiverConfig request) {
        if (request.failNext() != null) {
            failNext.set(request.failNext());
        }
        if (request.delayMs() != null) {
            responseDelayMs.set(Math.max(0, Math.min(request.delayMs(), 30_000)));
        }
        return Map.of("failNext", failNext.get(), "delayMs", responseDelayMs.get(), "secretConfigured", true);
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of("failNext", failNext.get(), "delayMs", responseDelayMs.get(), "secretConfigured", true);
    }

    private void delayResponse() {
        long delay = responseDelayMs.get();
        if (delay == 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receiver response interrupted");
        }
    }

    private boolean verify(String timestamp, String eventId, String payload, String signatureHeader) {
        try {
            String base = timestamp + "." + eventId + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return false;
        }
    }

    public record ReceiverConfig(Integer failNext, Long delayMs) {
    }
}
