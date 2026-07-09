package com.aipersimmon.ddd.inbox.jdbc;

import com.aipersimmon.ddd.application.Inbox;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records handled message keys in the inbox table. It relies on the table's unique
 * key: the first insert of a key succeeds (the message is new), and a second
 * insert of the same key fails with a duplicate-key error (the message was already
 * handled). Runs in the caller's transaction, so the record commits and rolls back
 * together with the processing.
 */
public class JdbcInbox implements Inbox {

    private static final String INSERT =
            "INSERT INTO aipersimmon_inbox (message_key, processed_at) VALUES (?, ?)";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcInbox(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean alreadyProcessed(String messageKey) {
        try {
            jdbc.update(INSERT, messageKey, Timestamp.from(clock.instant()));
            return false;
        } catch (DuplicateKeyException alreadyRecorded) {
            return true;
        }
    }
}
