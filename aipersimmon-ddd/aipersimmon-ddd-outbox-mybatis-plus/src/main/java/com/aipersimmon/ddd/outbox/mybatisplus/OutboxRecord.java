package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One stored integration event in the outbox table: the transport metadata, the
 * serialized JSON payload, and the delivery bookkeeping (sent flag, sent time,
 * attempt count). Uses MyBatis-Plus {@code @TableName}/{@code @TableId}, not a JPA
 * {@code @Entity}, so it never affects a consumer's JPA entity scanning. Column
 * names are the snake_case of the field names (MyBatis-Plus maps them by default).
 */
@TableName("aipersimmon_outbox")
public class OutboxRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String type;
    private Integer version;
    private String payload;
    private Instant occurredAt;
    private String traceId;
    private Boolean sent;
    private Instant sentAt;
    private Integer attempts;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Boolean getSent() {
        return sent;
    }

    public void setSent(Boolean sent) {
        this.sent = sent;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
