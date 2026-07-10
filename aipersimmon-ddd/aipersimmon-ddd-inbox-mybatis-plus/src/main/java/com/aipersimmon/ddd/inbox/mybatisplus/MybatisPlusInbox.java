package com.aipersimmon.ddd.inbox.mybatisplus;

import com.aipersimmon.ddd.application.Inbox;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;

/**
 * Records handled message keys through a MyBatis-Plus mapper. It relies on the
 * table's unique key: the first insert of a key succeeds (the message is new), and
 * a second insert of the same key fails with a duplicate-key error (the message
 * was already handled). Runs in the caller's transaction, so the record commits
 * and rolls back together with the processing.
 */
public class MybatisPlusInbox implements Inbox {

    private final InboxMapper mapper;
    private final Clock clock;

    public MybatisPlusInbox(InboxMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public boolean alreadyProcessed(String messageKey) {
        try {
            mapper.insert(new InboxRecord(messageKey, clock.instant()));
            return false;
        } catch (DuplicateKeyException alreadyRecorded) {
            return true;
        }
    }
}
