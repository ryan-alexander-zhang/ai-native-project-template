package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One dead-lettered message in the {@code aipersimmon_dead_letter} table: the same transport
 * metadata and payload as the outbox row it was moved from, plus the triage fields (attempts made,
 * reason given up on, the last error, when). Uses MyBatis-Plus {@code @TableName}/{@code @TableId}
 * like {@link OutboxRecord}, so it never affects a consumer's JPA entity scanning.
 */
@TableName("aipersimmon_dead_letter")
public class DeadLetterRecord {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String eventId;
  private String source;
  private String type;
  private Integer version;
  private String payload;
  private Instant occurredAt;
  private String subject;
  private String correlationId;
  private String causationId;
  private Integer attempts;
  private String reason;
  private String lastError;
  private Instant failedAt;

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

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
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

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }

  public String getCausationId() {
    return causationId;
  }

  public void setCausationId(String causationId) {
    this.causationId = causationId;
  }

  public Integer getAttempts() {
    return attempts;
  }

  public void setAttempts(Integer attempts) {
    this.attempts = attempts;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public Instant getFailedAt() {
    return failedAt;
  }

  public void setFailedAt(Instant failedAt) {
    this.failedAt = failedAt;
  }
}
