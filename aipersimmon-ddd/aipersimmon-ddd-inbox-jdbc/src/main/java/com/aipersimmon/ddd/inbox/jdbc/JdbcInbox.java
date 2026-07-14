package com.aipersimmon.ddd.inbox.jdbc;

import com.aipersimmon.ddd.application.Inbox;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records handled message keys in the inbox table, scoped to a configured
 * {@code consumer} (this application's identity), so several services sharing one
 * inbox table do not suppress one another's processing of the same
 * producer-assigned message id.
 *
 * <p>It checks for the key first and only inserts when absent. Doing the read first
 * keeps the common redelivery case — the key is already recorded — free of a
 * constraint violation, which matters on PostgreSQL where a failed insert marks the
 * whole transaction as aborted and would then fail the surrounding commit. The
 * unique key still guards the rare race of two simultaneous first-time deliveries:
 * the losing insert fails and its transaction rolls back, so the message is simply
 * redelivered and then detected as already processed.
 *
 * <p>Runs in the caller's transaction, so the record commits and rolls back together
 * with the processing.
 */
public class JdbcInbox implements Inbox {

    private static final String EXISTS =
            "SELECT COUNT(*) FROM aipersimmon_inbox WHERE consumer = ? AND message_key = ?";
    private static final String INSERT =
            "INSERT INTO aipersimmon_inbox (consumer, message_key, processed_at) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String consumer;

    public JdbcInbox(JdbcTemplate jdbc, Clock clock, String consumer) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.consumer = consumer;
    }

    @Override
    public boolean alreadyProcessed(String messageKey) {
        Integer count = jdbc.queryForObject(EXISTS, Integer.class, consumer, messageKey);
        if (count != null && count > 0) {
            return true;
        }
        jdbc.update(INSERT, consumer, messageKey, Timestamp.from(clock.instant()));
        return false;
    }
}
