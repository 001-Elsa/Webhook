package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.WebhookEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EndpointMatcherTest {
    private final EndpointMatcher matcher = new EndpointMatcher();

    @Test
    void matchesStringEquality() {
        assertTrue(supports("$.region==cn", Map.of("region", "cn")));
        assertFalse(supports("$.region==cn", Map.of("region", "us")));
        assertTrue(supports("$.region!=cn", Map.of("region", "us")));
    }

    @Test
    void matchesNumberAndBooleanLiterals() {
        assertTrue(supports("$.count==10", Map.of("count", 10)));
        assertTrue(supports("$.count==10", Map.of("count", 10.0)));
        assertFalse(supports("$.count==10", Map.of("count", 11)));
        assertTrue(supports("$.enabled==true", Map.of("enabled", true)));
        assertFalse(supports("$.enabled==true", Map.of("enabled", false)));
        assertTrue(supports("$.enabled!=false", Map.of("enabled", true)));
    }

    @Test
    void matchesAndClauses() {
        assertTrue(supports("$.region==cn && $.count==10", Map.of("region", "cn", "count", 10)));
        assertFalse(supports("$.region==cn && $.count==10", Map.of("region", "cn", "count", 9)));
        assertTrue(supports("$.a==1 && $.b==2 && $.c==true",
                Map.of("a", 1, "b", 2, "c", true)));
    }

    @Test
    void validatesAtCreateTime() {
        assertDoesNotThrow(() -> EndpointMatcher.validateFilterExpression("$.count==10"));
        assertDoesNotThrow(() -> EndpointMatcher.validateFilterExpression(
                "$.a==1 && $.b==2 && $.c==3 && $.d==4 && $.e==true"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointMatcher.validateFilterExpression("$.a==1 && $.b==2 && $.c==3 && $.d==4 && $.e==5 && $.f==6"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointMatcher.validateFilterExpression("payload.region==cn"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointMatcher.validateFilterExpression("$.region=~cn"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointMatcher.validateFilterExpression("$.x==1 || $.y==2"));
    }

    @Test
    void blankExpressionAllowsAll() {
        WebhookEndpoint endpoint = endpoint("*", " ");
        assertTrue(matcher.supports(endpoint, "order.created", Map.of()));
    }

    private boolean supports(String expression, Map<String, Object> data) {
        return matcher.supports(endpoint("*", expression), "order.created", data);
    }

    private static WebhookEndpoint endpoint(String types, String expression) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setEventTypes(types);
        endpoint.setFilterExpression(expression);
        return endpoint;
    }
}
