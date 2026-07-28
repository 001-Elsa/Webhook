package com.example.webhook.platform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eventrelay.roles.scheduler", havingValue = "true", matchIfMissing = true)
public class ReplayJobScheduler {
    private final ReplayJobService jobs;
    private final LeaderLeaseService leases;

    public ReplayJobScheduler(ReplayJobService jobs, LeaderLeaseService leases) {
        this.jobs = jobs;
        this.leases = leases;
    }

    @Scheduled(fixedDelayString = "${webhook.replay.poll-delay-ms:2000}")
    public void run() {
        if (leases.acquire("replay-job-scheduler", 30)) jobs.runNext();
    }
}
