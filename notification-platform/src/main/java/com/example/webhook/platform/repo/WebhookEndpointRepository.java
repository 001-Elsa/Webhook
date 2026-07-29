package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.domain.CircuitState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    List<WebhookEndpoint> findByActiveTrue();
    List<WebhookEndpoint> findByTenantId(String tenantId);
    List<WebhookEndpoint> findByTenantIdAndActiveTrue(String tenantId);
    Optional<WebhookEndpoint> findByIdAndTenantId(Long id, String tenantId);
    List<WebhookEndpoint> findByKeyVersionNot(int keyVersion, Pageable pageable);
    long countByTenantId(String tenantId);

    /**
     * A compare-and-set transition prevents two workers from resetting the probe
     * counter while an expired OPEN circuit moves into HALF_OPEN.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update WebhookEndpoint e
               set e.circuitState = :halfOpen, e.halfOpenProbes = 0
             where e.id = :id and e.circuitState = :open
               and (e.circuitOpenUntil is null or e.circuitOpenUntil <= :now)
            """)
    int transitionOpenToHalfOpen(@Param("id") Long id,
                                 @Param("open") CircuitState open,
                                 @Param("halfOpen") CircuitState halfOpen,
                                 @Param("now") Instant now);

    /**
     * Atomically reserves a HALF_OPEN probe.  The affected-row count is the
     * distributed permit: a worker must not infer permission from a stale entity.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update WebhookEndpoint e
               set e.halfOpenProbes = e.halfOpenProbes + 1
             where e.id = :id and e.circuitState = :halfOpen
               and e.halfOpenProbes < :maxProbes
            """)
    int acquireHalfOpenProbe(@Param("id") Long id,
                             @Param("halfOpen") CircuitState halfOpen,
                             @Param("maxProbes") int maxProbes);
}
