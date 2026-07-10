package com.aipersimmon.ddd.inbox.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One handled-message record in the inbox table. The message key is the primary
 * key (supplied by the caller, not generated), so inserting an already-recorded
 * key violates the unique constraint — that is how a redelivery is detected. Uses
 * MyBatis-Plus {@code @TableName}/{@code @TableId}, not a JPA {@code @Entity}, so
 * it never affects a consumer's JPA entity scanning.
 */
@TableName("aipersimmon_inbox")
public class InboxRecord {

    @TableId(type = IdType.INPUT)
    private String messageKey;
    private Instant processedAt;

    public InboxRecord() {
    }

    public InboxRecord(String messageKey, Instant processedAt) {
        this.messageKey = messageKey;
        this.processedAt = processedAt;
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
