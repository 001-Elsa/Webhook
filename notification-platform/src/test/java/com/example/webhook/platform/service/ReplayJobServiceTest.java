package com.example.webhook.platform.service;

import com.example.webhook.platform.api.dto.CreateReplayJobRequest;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.domain.ReplayJob;
import com.example.webhook.platform.domain.ReplayJobStatus;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.ReplayJobRepository;
import com.example.webhook.platform.security.ApiPrincipal;
import com.example.webhook.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayJobServiceTest {
    @Mock ReplayJobRepository jobs;
    @Mock DeliveryTaskRepository deliveries;
    @Mock DeliveryService deliveryService;
    @Mock AuditService audit;
    @Mock TransactionTemplate transactions;

    private SimpleMeterRegistry metrics;
    private ReplayJobService service;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        service = new ReplayJobService(jobs, deliveries, deliveryService, audit, transactions,
                metrics, new ObjectMapper(), true, 1_000, 10);
        RequestContext.set(new ApiPrincipal("tenant-a", "admin-a", ClientRole.ADMIN, Set.of("*")), "trace-1");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void createPersistsFiltersAndRequiresApprovalForRealReplay() {
        when(jobs.save(any())).thenAnswer(invocation -> {
            ReplayJob job = invocation.getArgument(0);
            ReflectionTestUtils.setField(job, "id", 11L);
            return job;
        });
        Instant after = Instant.parse("2026-01-01T00:00:00Z");
        Instant before = Instant.parse("2026-02-01T00:00:00Z");

        ReplayJob result = service.create(new CreateReplayJobRequest(
                false, 250, 7L, "order.created", after, before, 503));

        assertThat(result.getTenantId()).isEqualTo("tenant-a");
        assertThat(result.getRequestedBy()).isEqualTo("admin-a");
        assertThat(result.getStatus()).isEqualTo(ReplayJobStatus.AWAITING_APPROVAL);
        assertThat(result.getFilterJson())
                .contains("\"endpointId\":7", "\"eventType\":\"order.created\"",
                        "\"failedAfter\":\"2026-01-01T00:00:00Z\"", "\"httpStatus\":503");
        verify(audit).record(eq("REPLAY_JOB_CREATED"), eq("REPLAY_JOB"), eq("11"),
                eq("SUCCESS"), any());
    }

    @Test
    void approveCancelGetAndListRemainTenantScoped() {
        ReplayJob awaiting = job(12L, ReplayJobStatus.AWAITING_APPROVAL, false);
        when(jobs.findByIdAndTenantId(12L, "tenant-a")).thenReturn(Optional.of(awaiting));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.findTop100ByTenantIdOrderByCreatedAtDesc("tenant-a")).thenReturn(List.of(awaiting));
        RequestContext.set(new ApiPrincipal("tenant-a", "admin-b", ClientRole.ADMIN, Set.of("*")), "trace-2");

        ReplayJob approved = service.approve(12L);
        assertThat(approved.getStatus()).isEqualTo(ReplayJobStatus.PENDING);
        assertThat(approved.getApprovedBy()).isEqualTo("admin-b");
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(service.get(12L)).isSameAs(awaiting);
        assertThat(service.list()).containsExactly(awaiting);

        ReplayJob cancelled = service.cancel(12L);
        assertThat(cancelled.getStatus()).isEqualTo(ReplayJobStatus.CANCELLED);
        assertThat(cancelled.isCancellationRequested()).isTrue();
        assertThat(cancelled.getCompletedAt()).isNotNull();
    }

    @Test
    void approveRejectsTheJobCreator() {
        ReplayJob awaiting = job(16L, ReplayJobStatus.AWAITING_APPROVAL, false);
        when(jobs.findByIdAndTenantId(16L, "tenant-a")).thenReturn(Optional.of(awaiting));

        assertThatThrownBy(() -> service.approve(16L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creator cannot approve");
    }

    @Test
    void approveRejectsJobInWrongStateAndUnknownJobIsHidden() {
        ReplayJob completed = job(13L, ReplayJobStatus.COMPLETED, false);
        when(jobs.findByIdAndTenantId(13L, "tenant-a")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.approve(13L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not awaiting approval");
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runNextClaimsAtomicallyAndBuildsDryRunPreview() {
        ReplayJob dryRun = job(14L, ReplayJobStatus.PENDING, true);
        dryRun.setMaxDeliveries(20);
        dryRun.setFilterJson("""
                {"status":"DEAD","endpointId":7,"eventType":"order.created","httpStatus":503}
                """);
        DeliveryTask candidate = delivery(21L, 7L, 503, "connection timeout");

        when(jobs.findByStatusOrderByCreatedAtAsc(eq(ReplayJobStatus.PENDING), any()))
                .thenReturn(List.of(dryRun));
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(jobs.claimPending(eq(14L), any(), any(), anyString(), eq(ReplayJobStatus.RUNNING),
                eq(ReplayJobStatus.PENDING))).thenAnswer(invocation -> {
            dryRun.setStatus(ReplayJobStatus.RUNNING);
            dryRun.setLockedBy(invocation.getArgument(3));
            return 1;
        });
        when(jobs.heartbeat(eq(14L), anyString(), any(), any(), eq(ReplayJobStatus.RUNNING))).thenReturn(1);
        when(jobs.findById(14L)).thenReturn(Optional.of(dryRun));
        when(deliveries.findReplayCandidates(eq("tenant-a"), any(), eq(7L),
                eq("order.created"), any(), any(), eq(503), any()))
                .thenReturn(List.of(candidate));
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.runNext();

        verify(jobs).claimPending(eq(14L), any(), any(), anyString(), eq(ReplayJobStatus.RUNNING),
                eq(ReplayJobStatus.PENDING));
        assertThat(dryRun.getStatus()).isEqualTo(ReplayJobStatus.COMPLETED);
        assertThat(dryRun.getProcessedCount()).isEqualTo(1);
        assertThat(dryRun.getSkippedCount()).isEqualTo(1);
        assertThat(dryRun.getResultSummaryJson())
                .contains("\"deliveryId\":21", "\"endpointId\":7",
                        "\"503\":1", "\"HTTP_5XX\":1");
        assertThat(metrics.counter("webhook.replay.job.completed",
                "status", "COMPLETED").count()).isEqualTo(1.0);
    }

    @Test
    void runNextDoesNothingWhenQueueIsEmptyOrClaimLosesRace() {
        when(jobs.findByStatusOrderByCreatedAtAsc(eq(ReplayJobStatus.PENDING), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(job(15L, ReplayJobStatus.PENDING, true)));
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(jobs.claimPending(eq(15L), any(), any(), anyString(), eq(ReplayJobStatus.RUNNING),
                eq(ReplayJobStatus.PENDING))).thenReturn(0);

        service.runNext();
        service.runNext();

        verify(jobs, never()).findById(any());
    }

    @Test
    void schedulerRecoversExpiredRunningJobsBeforeClaimingNewWork() {
        when(jobs.recoverExpiredRunning(any(), eq(ReplayJobStatus.PENDING), eq(ReplayJobStatus.RUNNING))).thenReturn(1);
        when(jobs.findByStatusOrderByCreatedAtAsc(eq(ReplayJobStatus.PENDING), any())).thenReturn(List.of());

        service.runNext();

        verify(jobs).recoverExpiredRunning(any(), eq(ReplayJobStatus.PENDING), eq(ReplayJobStatus.RUNNING));
        assertThat(metrics.counter("webhook.replay.job.recovered").count()).isEqualTo(1.0);
    }

    private static ReplayJob job(long id, ReplayJobStatus status, boolean dryRun) {
        ReplayJob job = new ReplayJob();
        ReflectionTestUtils.setField(job, "id", id);
        job.setTenantId("tenant-a");
        job.setRequestedBy("admin-a");
        job.setStatus(status);
        job.setDryRun(dryRun);
        job.setMaxDeliveries(100);
        return job;
    }

    private static DeliveryTask delivery(long id, long endpointId, int statusCode, String error) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        ReflectionTestUtils.setField(endpoint, "id", endpointId);
        endpoint.setName("receiver");
        DeliveryTask task = new DeliveryTask();
        ReflectionTestUtils.setField(task, "id", id);
        task.setEndpoint(endpoint);
        task.setLastStatusCode(statusCode);
        task.setLastError(error);
        return task;
    }
}
