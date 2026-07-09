package com.aipersimmon.ddd.outbox.jdbc;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls the outbox for unsent rows and dispatches them, marking each sent on
 * success. A failed dispatch leaves the row unsent (its attempt count is bumped)
 * so it is retried on the next poll — at-least-once delivery. Each row is marked
 * on its own, so one failure does not undo already-dispatched rows.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String SELECT_UNSENT =
            "SELECT event_id, type, version, payload, occurred_at, trace_id "
            + "FROM aipersimmon_outbox WHERE sent = FALSE ORDER BY created_at ASC LIMIT ?";
    private static final String MARK_SENT =
            "UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = ? WHERE event_id = ?";
    private static final String BUMP_ATTEMPTS =
            "UPDATE aipersimmon_outbox SET attempts = attempts + 1 WHERE event_id = ?";

    private final JdbcTemplate jdbc;
    private final OutboxDispatcher dispatcher;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(JdbcTemplate jdbc, OutboxDispatcher dispatcher, Clock clock, int batchSize) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
    public void relay() {
        List<OutboxMessage> batch = jdbc.query(SELECT_UNSENT, this::mapRow, batchSize);
        for (OutboxMessage message : batch) {
            try {
                dispatcher.dispatch(message);
                jdbc.update(MARK_SENT, Timestamp.from(clock.instant()), message.eventId());
            } catch (RuntimeException e) {
                jdbc.update(BUMP_ATTEMPTS, message.eventId());
                log.warn("outbox dispatch failed for eventId={}, will retry", message.eventId(), e);
            }
        }
    }

    private OutboxMessage mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxMessage(
                rs.getString("event_id"),
                rs.getString("type"),
                rs.getInt("version"),
                rs.getString("payload"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("trace_id"));
    }
}
