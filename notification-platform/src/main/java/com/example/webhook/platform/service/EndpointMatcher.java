package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.WebhookEndpoint;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic endpoint subscription filter DSL only — not a Schema Registry.
 * Supports {@code $.field==value} / {@code !=} for string, number, and boolean
 * literals, combined with {@code &&} (max 5 clauses). No scripts.
 */
@Component
public class EndpointMatcher {
    private static final int MAX_CLAUSES = 5;
    private static final Pattern CLAUSE = Pattern.compile(
            "^\\$\\.([A-Za-z0-9_.-]{1,200})\\s*(==|!=)\\s*"
                    + "(true|false|-?\\d+(?:\\.\\d+)?|[A-Za-z0-9 _.-]{1,200})$");

    public boolean supports(WebhookEndpoint endpoint, String eventType) {
        return supports(endpoint, eventType, Map.of());
    }

    public static void validateFilterExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        String[] clauses = splitClauses(expression.trim());
        if (clauses.length > MAX_CLAUSES) {
            throw new IllegalArgumentException(
                    "Unsupported endpoint filter expression: at most " + MAX_CLAUSES + " clauses");
        }
        for (String clause : clauses) {
            if (!CLAUSE.matcher(clause.trim()).matches()) {
                throw new IllegalArgumentException("Unsupported endpoint filter expression");
            }
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
        if (!typeMatches) {
            return false;
        }
        String expression = endpoint.getFilterExpression();
        if (expression == null || expression.isBlank()) {
            return true;
        }
        validateFilterExpression(expression);
        for (String clause : splitClauses(expression.trim())) {
            if (!evaluateClause(clause.trim(), data)) {
                return false;
            }
        }
        return true;
    }

    private static String[] splitClauses(String expression) {
        return expression.split("\\s*&&\\s*");
    }

    private boolean evaluateClause(String clause, Map<String, Object> data) {
        Matcher matcher = CLAUSE.matcher(clause);
        if (!matcher.matches()) {
            return false;
        }
        Object value = nested(data, matcher.group(1));
        boolean equal = valuesEqual(value, matcher.group(3));
        return "==".equals(matcher.group(2)) ? equal : !equal;
    }

    static boolean valuesEqual(Object actual, String literal) {
        if (actual == null) {
            return false;
        }
        if ("true".equals(literal) || "false".equals(literal)) {
            boolean expected = Boolean.parseBoolean(literal);
            if (actual instanceof Boolean bool) {
                return bool == expected;
            }
            return String.valueOf(actual).equalsIgnoreCase(literal);
        }
        if (isNumberLiteral(literal)) {
            double expected = Double.parseDouble(literal);
            if (actual instanceof Number number) {
                return Double.compare(number.doubleValue(), expected) == 0;
            }
            try {
                return Double.compare(Double.parseDouble(String.valueOf(actual)), expected) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return literal.equals(String.valueOf(actual));
    }

    private static boolean isNumberLiteral(String literal) {
        return literal.matches("-?\\d+(?:\\.\\d+)?");
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> data, String path) {
        Object current = data;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }
}
