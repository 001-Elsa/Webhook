package com.example.webhook.platform.incident;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedIncidentAdvisorEvaluationTest {
    private final RuleBasedIncidentAdvisor advisor = new RuleBasedIncidentAdvisor();

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void classifiesDeterministicFailureSet(String name, Integer status, String error,
                                           long backlog, String expectedCategory) {
        IncidentContext context = new IncidentContext(1L, "tenant-a", "DEAD", status, 3,
                false, false, backlog,
                List.of(new IncidentContext.Evidence("evidence:1", "SANITIZED_ERROR", error)),
                Map.of(), "runbook");

        IncidentDiagnosis result = advisor.diagnose(context).orElseThrow();

        assertThat(result.category()).isEqualTo(expectedCategory);
        assertThat(result.evidenceIds()).containsExactly("evidence:1");
        assertThat(result.recommendedActions()).isNotEmpty();
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("401 signature", 401, "", 0, "RECEIVER_AUTHENTICATION"),
                Arguments.of("403 permission", 403, "", 0, "RECEIVER_PERMISSION"),
                Arguments.of("404 endpoint", 404, "", 0, "RECEIVER_ENDPOINT_CHANGED"),
                Arguments.of("408 timeout", 408, "", 0, "RECEIVER_TIMEOUT"),
                Arguments.of("429 rate limit", 429, "", 0, "RECEIVER_RATE_LIMIT"),
                Arguments.of("500 receiver", 500, "", 0, "RECEIVER_SERVER_ERROR"),
                Arguments.of("503 receiver", 503, "", 0, "RECEIVER_SERVER_ERROR"),
                Arguments.of("dns", null, "UnknownHostException DNS", 0, "DNS_FAILURE"),
                Arguments.of("tls", null, "SSL certificate failure", 0, "TLS_FAILURE"),
                Arguments.of("refused", null, "Connection refused", 0, "CONNECTION_REFUSED"),
                Arguments.of("backlog", null, "", 2000, "OUTBOX_BACKLOG"),
                Arguments.of("uncertain", null, "", 0, "UNDETERMINED")
        );
    }
}
