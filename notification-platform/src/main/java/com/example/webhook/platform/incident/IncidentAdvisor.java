package com.example.webhook.platform.incident;

import java.util.Optional;

public interface IncidentAdvisor {
    Optional<IncidentDiagnosis> diagnose(IncidentContext context);
}
