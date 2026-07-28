package com.example.webhook.platform.incident;

import java.util.List;

public record IncidentDiagnosis(
        String category,
        double confidence,
        String summary,
        List<String> evidenceIds,
        List<String> recommendedActions,
        boolean replayRecommended,
        String analyzer,
        String model,
        String promptVersion
) { }
