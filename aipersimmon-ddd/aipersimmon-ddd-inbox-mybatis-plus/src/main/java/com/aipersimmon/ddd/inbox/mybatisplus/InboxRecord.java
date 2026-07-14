package com.aipersimmon.ddd.inbox.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One handled-message record in the inbox table, keyed by the composite
 * (consumer, message_key): the consumer scopes dedup to one consuming application,
 * and the message key is the caller-supplied id (not generated). Uses MyBatis-Plus
 * {@code @TableName}/{@code @TableId}, not a JPA {@code @Entity}, so it never
 * affects a consumer's JPA entity scanning. The inbox reads before inserting, so
 * the composite primary key is a safety net rather than the normal dedup path.
 */
@TableName("aipersimmon_inbox")
public class InboxRecord {

    private String consumer;
    @TableId(type = IdType.INPUT)
    private String messageKey;
    private Instant processedAt;

    public InboxRecord() {
    }

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
