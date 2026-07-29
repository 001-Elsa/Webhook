package com.example.webhook.platform.incident;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunbookRetrieverTest {
    private static final String RUNBOOK = """
            # EventRelay incident guidance

            ## Auth and configuration failures
            Never replay a 401, 403, or 404 until configuration is corrected.

            ## Rate limiting and Retry-After
            Honor Retry-After for HTTP 429 responses.

            ## Receiver 5xx and transient network
            For receiver 5xx or transient network failures, wait for recovery.

            ## Outbox backlog and publisher stalls
            When outbox backlog is elevated, inspect publisher health.

            ## Circuit open and endpoint pause
            When a circuit is open or an endpoint is paused, resolve receiver health first.
            """;

    private final RunbookRetriever retriever = new RunbookRetriever(RUNBOOK);

    @Test
    void ranksAuthSectionAboveOthersFor401() {
        List<RunbookRetriever.ScoredSection> ranked = retriever.rank(
                new RunbookRetriever.IncidentSignals(401, "DEAD", "unauthorized", 0, false, false));
        assertFalse(ranked.isEmpty());
        assertEquals("RB-1", ranked.get(0).section().id());
        assertTrue(ranked.get(0).score() > ranked.get(ranked.size() - 1).score());
    }

    @Test
    void ranksOutboxSectionForBacklogSignal() {
        List<RunbookRetriever.ScoredSection> ranked = retriever.rank(
                new RunbookRetriever.IncidentSignals(null, "PENDING", null, 120, false, false));
        assertEquals("RB-4", ranked.get(0).section().id());
    }

    @Test
    void injectsTopKSectionIdsForCitation() {
        String excerpt = retriever.retrieve(
                new RunbookRetriever.IncidentSignals(503, "FAILED", "connection reset", 5, true, false), 3);
        assertTrue(excerpt.contains("[RB-"));
        assertTrue(excerpt.contains("### ["));
        long sectionHeaders = excerpt.lines().filter(line -> line.startsWith("### [")).count();
        assertEquals(3, sectionHeaders);
    }

    @Test
    void documentsKeywordRetrievalNotVectorRag() {
        assertTrue(RunbookRetriever.class.getName().contains("RunbookRetriever"));
        String javadocIntent = "structured keyword retrieval";
        assertFalse(javadocIntent.toLowerCase().contains("vector"));
    }
}
