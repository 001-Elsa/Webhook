package com.example.webhook.platform.incident;

import org.springframework.stereotype.Component;

@Component
public class SensitiveDataRedactor {
    public String redact(String value) {
        if (value == null) return "";
        String sanitized = value
                .replaceAll("(?i)authorization\\s*[:=]\\s*bearer\\s+[A-Za-z0-9._~+/=-]+",
                        "Authorization=[REDACTED]")
                .replaceAll("(?i)(authorization|api[-_ ]?key|secret|signature)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[REDACTED]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("([?&](?:token|key|secret|signature)=)[^&\\s]+", "$1[REDACTED]");
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }
}
