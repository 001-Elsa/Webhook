package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.CreateReplayJobRequest;
import com.example.webhook.platform.api.dto.ReplayJobResponse;
import com.example.webhook.platform.service.ReplayJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/replay-jobs")
public class ReplayJobController {
    private final ReplayJobService jobs;

    public ReplayJobController(ReplayJobService jobs) { this.jobs = jobs; }

    @PostMapping
    public ReplayJobResponse create(@Valid @RequestBody CreateReplayJobRequest request) {
        return ReplayJobResponse.from(jobs.create(request));
    }

    @GetMapping
    public List<ReplayJobResponse> list() {
        return jobs.list().stream().map(ReplayJobResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ReplayJobResponse get(@PathVariable Long id) { return ReplayJobResponse.from(jobs.get(id)); }

    @PostMapping("/{id}/approve")
    public ReplayJobResponse approve(@PathVariable Long id) { return ReplayJobResponse.from(jobs.approve(id)); }

    @PostMapping("/{id}/cancel")
    public ReplayJobResponse cancel(@PathVariable Long id) { return ReplayJobResponse.from(jobs.cancel(id)); }
}
