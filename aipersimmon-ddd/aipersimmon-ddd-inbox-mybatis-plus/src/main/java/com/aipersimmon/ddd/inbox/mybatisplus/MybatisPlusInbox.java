package com.aipersimmon.ddd.inbox.mybatisplus;

import com.aipersimmon.ddd.application.Inbox;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;

/**
 * Records handled message keys through a MyBatis-Plus mapper, scoped to a configured {@code
 * consumer} (this application's identity), so several services sharing one inbox table do not
 * suppress one another's processing of the same producer-assigned message id.
 *
 * <p>It checks for the key first and only inserts when absent. Doing the read first keeps the
 * common redelivery case — the key is already recorded — free of a constraint violation, which
 * matters on PostgreSQL where a failed insert marks the whole transaction as aborted and would then
 * fail the surrounding commit. The composite primary key still guards the rare race of two
 * simultaneous first-time deliveries: the losing insert fails and its transaction rolls back, so
 * the message is simply redelivered and then detected as already processed.
 *
 * <p>Runs in the caller's transaction, so the record commits and rolls back together with the
 * processing.
 */
public class MybatisPlusInbox implements Inbox {

  private final InboxMapper mapper;
  private final Clock clock;
  private final String consumer;

  public MybatisPlusInbox(InboxMapper mapper, Clock clock, String consumer) {
    this.mapper = mapper;
    this.clock = clock;
    this.consumer = consumer;
  }

  @Override
  public boolean alreadyProcessed(String messageKey) {
    Long count =
        mapper.selectCount(
            new LambdaQueryWrapper<InboxRecord>()
                .eq(InboxRecord::getConsumer, consumer)
                .eq(InboxRecord::getMessageKey, messageKey));
    if (count != null && count > 0) {
      return true;
    }
    mapper.insert(new InboxRecord(consumer, messageKey, clock.instant()));
    return false;
  }
}
