package com.aipersimmon.ddd.processmanager.jdbc.lease;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Claim strategy for databases without {@code SKIP LOCKED} (for example H2 in tests). It
 * selects the due candidates unlocked, then races each into {@code IN_FLIGHT} with an
 * atomic conditional {@code UPDATE} that succeeds only if the row is still due — so two
 * workers cannot both win the same effect. The candidate query's head-of-line predicate
 * still guarantees per-instance ordering.
 */
public final class AtomicUpdateProcessDialect implements JdbcProcessDialect {

    private final String id;

    public AtomicUpdateProcessDialect(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<String> claimDueEffects(
            JdbcTemplate jdbc, Instant now, int limit, WorkerId owner, String leaseToken, Instant leaseUntil) {
        Timestamp ts = Timestamp.from(now);
        List<String> candidates = jdbc.query(
                CANDIDATE_SQL + " LIMIT " + limit, (rs, n) -> rs.getString(1), ts, ts);
        List<String> claimed = new ArrayList<>();
        for (String id : candidates) {
            int won = jdbc.update("""
                    UPDATE aipersimmon_process_effect
                    SET status = 'IN_FLIGHT', attempts = attempts + 1,
                        lease_owner = ?, lease_token = ?, lease_until = ?, updated_at = ?
                    WHERE effect_id = ?
                      AND ((status = 'PENDING' AND next_attempt_at <= ?)
                           OR (status = 'IN_FLIGHT' AND lease_until <= ?))""",
                    owner.value(), leaseToken, Timestamp.from(leaseUntil), ts, id, ts, ts);
            if (won == 1) {
                claimed.add(id);
            }
        }
        return claimed;
    }
}
