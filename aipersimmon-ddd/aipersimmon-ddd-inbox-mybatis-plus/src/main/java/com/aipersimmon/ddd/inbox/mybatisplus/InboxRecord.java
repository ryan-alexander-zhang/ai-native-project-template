package com.aipersimmon.ddd.inbox.mybatisplus;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One handled-message record in the inbox table, keyed by the composite (consumer, message_key):
 * the consumer scopes dedup to one consuming application, and the message key is the
 * caller-supplied id (not generated). Uses MyBatis-Plus {@code @TableName}, not a JPA
 * {@code @Entity}, so it never affects a consumer's JPA entity scanning. The inbox reads before
 * inserting, so the composite primary key is a safety net rather than the normal dedup path.
 *
 * <p>No field is marked {@code @TableId}: the row's identity is the composite (consumer,
 * message_key), which MyBatis-Plus cannot express as a single id, and message_key alone is not
 * unique across consumers. Every access therefore goes through consumer-scoped {@code
 * LambdaQueryWrapper}s (see {@link MybatisPlusInbox}, {@link InboxCleanup}); id-based BaseMapper
 * methods ({@code selectById}/{@code deleteById}/ {@code updateById}) are deliberately not
 * generated, so a row cannot be addressed by message_key alone and silently clobber another
 * consumer's dedup state.
 */
@TableName("aipersimmon_inbox")
public class InboxRecord {

  private String consumer;
  private String messageKey;
  private Instant processedAt;

  public InboxRecord() {}

  public InboxRecord(String consumer, String messageKey, Instant processedAt) {
    this.consumer = consumer;
    this.messageKey = messageKey;
    this.processedAt = processedAt;
  }

  public String getConsumer() {
    return consumer;
  }

  public void setConsumer(String consumer) {
    this.consumer = consumer;
  }

  public String getMessageKey() {
    return messageKey;
  }

  public void setMessageKey(String messageKey) {
    this.messageKey = messageKey;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  public void setProcessedAt(Instant processedAt) {
    this.processedAt = processedAt;
  }
}
