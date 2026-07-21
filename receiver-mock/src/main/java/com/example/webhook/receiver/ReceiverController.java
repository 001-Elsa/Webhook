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

@RestController
public class ReceiverController {
    private final List<ReceivedWebhook> received = new ArrayList<>();
    private final AtomicInteger failNext = new AtomicInteger(1);
    private final Set<String> processedDeliveries = ConcurrentHashMap.newKeySet();
    private volatile String secret = "demo-secret";

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
        if (request.secret() != null && !request.secret().isBlank()) {
            secret = request.secret();
        }
        return Map.of("failNext", failNext.get(), "secret", secret);
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of("failNext", failNext.get(), "secret", secret);
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

    public record ReceiverConfig(Integer failNext, String secret) {
    }
}
