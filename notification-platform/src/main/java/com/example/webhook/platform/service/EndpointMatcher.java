package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.WebhookEndpoint;
import org.springframework.stereotype.Component;
import java.util.Arrays;

@Component
public class EndpointMatcher {
    public boolean supports(WebhookEndpoint endpoint, String eventType) {
        String config = endpoint.getEventTypes();
        if (config == null || config.isBlank() || "*".equals(config.trim())) {
            return true;
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .anyMatch(item -> item.equals(eventType) || item.equals("*"));
    }
}
