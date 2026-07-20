package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backs the {@link DeadLetterStore} with an {@code aipersimmon_dead_letter} table that
 * mirrors the outbox row plus triage columns ({@code attempts}, {@code reason},
 * {@code last_error}, {@code failed_at}). Every move runs in one {@link TransactionTemplate}
 * transaction — the dead-letter insert and the outbox delete commit together — so a
 * message is never duplicated across the two tables nor lost between them.
 */
public class JdbcDeadLetterStore implements DeadLetterStore {

    private static final String INSERT_DEAD_LETTER =
            "INSERT INTO aipersimmon_dead_letter "
            + "(event_id, source, type, version, payload, occurred_at, subject, "
            + "correlation_id, causation_id, attempts, reason, last_error, failed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String DELETE_OUTBOX =
            "DELETE FROM aipersimmon_outbox WHERE event_id = ?";

    private static final String SELECT_DEAD_LETTER =
            "SELECT event_id, source, type, version, payload, occurred_at, subject, "
            + "correlation_id, causation_id "
            + "FROM aipersimmon_dead_letter WHERE event_id = ?";
    private static final String REQUEUE_OUTBOX =
            "INSERT INTO aipersimmon_outbox "
            + "(event_id, source, type, version, payload, occurred_at, subject, "
            + "correlation_id, causation_id, sent, attempts, next_attempt_at, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, 0, NULL, ?)";
    private static final String DELETE_DEAD_LETTER =
            "DELETE FROM aipersimmon_dead_letter WHERE event_id = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public JdbcDeadLetterStore(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public void store(OutboxMessage message, int attempts, Reason reason, String lastError) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update(INSERT_DEAD_LETTER,
                    message.eventId(),
                    message.source(),
                    message.type(),
                    message.version(),
                    message.payload(),
                    Timestamp.from(message.occurredAt()),
                    message.subject(),
                    message.correlationId(),
                    message.causationId(),
                    attempts,
                    reason.name(),
                    lastError,
                    Timestamp.from(clock.instant()));
            jdbc.update(DELETE_OUTBOX, message.eventId());
        });
    }

    @Override
    public boolean replay(String eventId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            OutboxMessage message = jdbc.query(SELECT_DEAD_LETTER,
                    rs -> rs.next() ? new OutboxMessage(
                            rs.getString("event_id"),
                            rs.getString("source"),
                            rs.getString("type"),
                            rs.getInt("version"),
                            rs.getString("payload"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("subject"),
                            rs.getString("correlation_id"),
                            rs.getString("causation_id")) : null,
                    eventId);
            if (message == null) {
                return false;
            }
            jdbc.update(REQUEUE_OUTBOX,
                    message.eventId(),
                    message.source(),
                    message.type(),
                    message.version(),
                    message.payload(),
                    Timestamp.from(message.occurredAt()),
                    message.subject(),
                    message.correlationId(),
                    message.causationId(),
                    Timestamp.from(clock.instant()));
            jdbc.update(DELETE_DEAD_LETTER, eventId);
            return true;
        }));
    }
}
