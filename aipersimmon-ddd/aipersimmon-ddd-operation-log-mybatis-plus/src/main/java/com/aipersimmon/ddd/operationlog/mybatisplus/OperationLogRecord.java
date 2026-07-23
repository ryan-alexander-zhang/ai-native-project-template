package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * The {@code aipersimmon_operation_log} row as a MyBatis-Plus entity. Uses
 * {@code @TableName}/{@code @TableId} (assigned id, not auto) so it never affects a consumer's JPA
 * entity scanning; columns map from camelCase via the map-underscore-to-camel-case setting. {@code
 * changes}/{@code details} hold bounded JSON strings; the timestamps are UTC {@link
 * OffsetDateTime}.
 */
@TableName("aipersimmon_operation_log")
@SuppressWarnings("PMD.TooManyMethods") // a flat 27-column data holder: getters/setters only
public class OperationLogRecord {

  @TableId(value = "record_id", type = IdType.INPUT)
  private String recordId;

  private String source;
  private String tenantId;
  private String idempotencyKey;
  private String operationCode;
  private String actorType;
  private String actorId;
  private String actorDisplay;
  private String targetType;
  private String targetId;
  private String targetDisplay;
  private String outcome;
  private String completion;
  private String summary;
  private String changes;
  private String details;
  private String failureCode;
  private String failureCategory;
  private String failureSummary;
  private String messageId;
  private String correlationId;
  private String causationId;
  private String templateKey;
  private String templateVersion;
  private Integer schemaVersion;
  private OffsetDateTime occurredAt;
  private OffsetDateTime recordedAt;

  public String getRecordId() {
    return recordId;
  }

  public void setRecordId(String recordId) {
    this.recordId = recordId;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getOperationCode() {
    return operationCode;
  }

  public void setOperationCode(String operationCode) {
    this.operationCode = operationCode;
  }

  public String getActorType() {
    return actorType;
  }

  public void setActorType(String actorType) {
    this.actorType = actorType;
  }

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String actorId) {
    this.actorId = actorId;
  }

  public String getActorDisplay() {
    return actorDisplay;
  }

  public void setActorDisplay(String actorDisplay) {
    this.actorDisplay = actorDisplay;
  }

  public String getTargetType() {
    return targetType;
  }

  public void setTargetType(String targetType) {
    this.targetType = targetType;
  }

  public String getTargetId() {
    return targetId;
  }

  public void setTargetId(String targetId) {
    this.targetId = targetId;
  }

  public String getTargetDisplay() {
    return targetDisplay;
  }

  public void setTargetDisplay(String targetDisplay) {
    this.targetDisplay = targetDisplay;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getCompletion() {
    return completion;
  }

  public void setCompletion(String completion) {
    this.completion = completion;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public String getChanges() {
    return changes;
  }

  public void setChanges(String changes) {
    this.changes = changes;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String failureCode) {
    this.failureCode = failureCode;
  }

  public String getFailureCategory() {
    return failureCategory;
  }

  public void setFailureCategory(String failureCategory) {
    this.failureCategory = failureCategory;
  }

  public String getFailureSummary() {
    return failureSummary;
  }

  public void setFailureSummary(String failureSummary) {
    this.failureSummary = failureSummary;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
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

  public String getTemplateKey() {
    return templateKey;
  }

  public void setTemplateKey(String templateKey) {
    this.templateKey = templateKey;
  }

  public String getTemplateVersion() {
    return templateVersion;
  }

  public void setTemplateVersion(String templateVersion) {
    this.templateVersion = templateVersion;
  }

  public Integer getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(OffsetDateTime occurredAt) {
    this.occurredAt = occurredAt;
  }

  public OffsetDateTime getRecordedAt() {
    return recordedAt;
  }

  public void setRecordedAt(OffsetDateTime recordedAt) {
    this.recordedAt = recordedAt;
  }
}
