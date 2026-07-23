package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;

/** Flat projection of an effect operator-worklist row. */
public class EffectViewRow {
  private String effectId;
  private String instanceId;
  private String effectKind;
  private String status;
  private int attempts;
  private String messageId;
  private Timestamp nextAttemptAt;
  private String lastError;
  private Timestamp createdAt;

  public String getEffectId() {
    return effectId;
  }

  public void setEffectId(String v) {
    this.effectId = v;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String v) {
    this.instanceId = v;
  }

  public String getEffectKind() {
    return effectKind;
  }

  public void setEffectKind(String v) {
    this.effectKind = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    this.status = v;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int v) {
    this.attempts = v;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String v) {
    this.messageId = v;
  }

  public Timestamp getNextAttemptAt() {
    return nextAttemptAt == null ? null : (Timestamp) nextAttemptAt.clone();
  }

  public void setNextAttemptAt(Timestamp v) {
    this.nextAttemptAt = v == null ? null : (Timestamp) v.clone();
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String v) {
    this.lastError = v;
  }

  public Timestamp getCreatedAt() {
    return createdAt == null ? null : (Timestamp) createdAt.clone();
  }

  public void setCreatedAt(Timestamp v) {
    this.createdAt = v == null ? null : (Timestamp) v.clone();
  }
}
