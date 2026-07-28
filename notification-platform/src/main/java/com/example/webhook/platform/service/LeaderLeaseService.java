package com.example.webhook.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class LeaderLeaseService {
    private final String owner = UUID.randomUUID().toString();
    private final JdbcTemplate jdbc;

    public LeaderLeaseService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean acquire(String leaseName, int seconds) {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(seconds);
        jdbc.update("""
                insert into scheduler_leases (lease_name, owner_id, locked_until, updated_at)
                values (?, ?, ?, ?)
                on duplicate key update
                  owner_id=if(locked_until < values(updated_at) or owner_id=values(owner_id),
                              values(owner_id), owner_id),
                  locked_until=if(locked_until < values(updated_at) or owner_id=values(owner_id),
                                  values(locked_until), locked_until),
                  updated_at=values(updated_at)
                """, leaseName, owner, Timestamp.from(until), Timestamp.from(now));
        String currentOwner = jdbc.queryForObject(
                "select owner_id from scheduler_leases where lease_name=?", String.class, leaseName);
        return owner.equals(currentOwner);
    }
}
