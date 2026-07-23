package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;

/** Flat projection of a deadline operator-worklist row. */
public class DeadlineViewRow {
  private String deadlineId;
  private String instanceId;
  private String name;
  private long generation;
  private String status;
  private Timestamp dueAt;
  private int attempts;
  private Timestamp nextAttemptAt;
  private String lastError;

  public String getDeadlineId() {
    return deadlineId;
  }

  public void setDeadlineId(String v) {
    this.deadlineId = v;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String v) {
    this.instanceId = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    this.name = v;
  }

  public long getGeneration() {
    return generation;
  }

  public void setGeneration(long v) {
    this.generation = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    this.status = v;
  }

  public Timestamp getDueAt() {
    return dueAt == null ? null : (Timestamp) dueAt.clone();
  }

  public void setDueAt(Timestamp v) {
    this.dueAt = v == null ? null : (Timestamp) v.clone();
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int v) {
    this.attempts = v;
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
}
