package com.example.webhook.platform.api;

import com.example.webhook.platform.incident.IncidentDiagnosis;
import com.example.webhook.platform.incident.IncidentDiagnosisService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deliveries/{deliveryId}/diagnosis")
public class IncidentDiagnosisController {
    private final IncidentDiagnosisService diagnoses;

    public IncidentDiagnosisController(IncidentDiagnosisService diagnoses) { this.diagnoses = diagnoses; }

    @PostMapping
    public IncidentDiagnosis diagnose(@PathVariable Long deliveryId) {
        return diagnoses.diagnose(deliveryId);
    }
}
