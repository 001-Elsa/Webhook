package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.WebhookEndpoint;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EndpointMatcher {
    private static final Pattern FILTER = Pattern.compile(
            "^\\$\\.([A-Za-z0-9_.-]{1,200})\\s*(==|!=)\\s*([A-Za-z0-9 _.-]{1,200})$");

    public boolean supports(WebhookEndpoint endpoint, String eventType) {
        return supports(endpoint, eventType, Map.of());
    }

    public static void validateFilterExpression(String expression) {
        if (expression != null && !expression.isBlank() && !FILTER.matcher(expression.trim()).matches()) {
            throw new IllegalArgumentException("Unsupported endpoint filter expression");
        }
    }

    /**
     * A deliberately small deterministic filter language. It cannot execute
     * scripts, allocate objects from payload values, or access secrets.
     */
    public boolean supports(WebhookEndpoint endpoint, String eventType, Map<String, Object> data) {
        String config = endpoint.getEventTypes();
        boolean typeMatches = config == null || config.isBlank() || "*".equals(config.trim())
                || Arrays.stream(config.split(",")).map(String::trim)
                .anyMatch(item -> item.equals(eventType) || item.equals("*"));
        if (!typeMatches) return false;
        String expression = endpoint.getFilterExpression();
        if (expression == null || expression.isBlank()) return true;
        validateFilterExpression(expression);
        Matcher matcher = FILTER.matcher(expression.trim());
        matcher.matches();
        Object value = nested(data, matcher.group(1));
        boolean equal = value != null && matcher.group(3).equals(String.valueOf(value));
        return "==".equals(matcher.group(2)) ? equal : !equal;
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> data, String path) {
        Object current = data;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }
}
