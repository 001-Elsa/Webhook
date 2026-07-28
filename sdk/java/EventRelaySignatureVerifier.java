package io.eventrelay.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EventRelaySignatureVerifier {
    private EventRelaySignatureVerifier() { }

    public static boolean verify(String secret, String timestampMs, String eventId, byte[] rawBody,
                                 String signatureHeader, long toleranceSeconds) {
        try {
            long timestamp = Long.parseLong(timestampMs);
            if (Math.abs(Clock.systemUTC().millis() - timestamp) > toleranceSeconds * 1000) return false;
            Map<String, String> values = Stream.of(signatureHeader.split(","))
                    .map(value -> value.split("=", 2))
                    .filter(parts -> parts.length == 2)
                    .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (a, b) -> b));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampMs + "." + eventId + ".").getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    values.getOrDefault("v1", "").getBytes(StandardCharsets.US_ASCII));
        } catch (Exception invalid) {
            return false;
        }
    }
}
