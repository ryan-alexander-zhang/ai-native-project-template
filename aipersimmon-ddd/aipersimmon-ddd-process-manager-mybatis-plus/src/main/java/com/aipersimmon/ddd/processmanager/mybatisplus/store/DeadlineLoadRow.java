package com.aipersimmon.ddd.processmanager.mybatisplus.store;

/** Flat projection of a deadline row loaded for firing. */
public class DeadlineLoadRow {
  private String deadlineId;
  private String instanceId;
  private String name;
  private long generation;
  private String inputType;
  private int inputVersion;
  private String inputPayload;
  private String correlationId;
  private String causationId;
  private int attempts;
  private String traceparent;
  private String traceState;

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

  public String getInputType() {
    return inputType;
  }

  public void setInputType(String v) {
    this.inputType = v;
  }

  public int getInputVersion() {
    return inputVersion;
  }

  public void setInputVersion(int v) {
    this.inputVersion = v;
  }

  public String getInputPayload() {
    return inputPayload;
  }

  public void setInputPayload(String v) {
    this.inputPayload = v;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String v) {
    this.correlationId = v;
  }

  public String getCausationId() {
    return causationId;
  }

  public void setCausationId(String v) {
    this.causationId = v;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int v) {
    this.attempts = v;
  }

  public String getTraceparent() {
    return traceparent;
  }

  public void setTraceparent(String v) {
    this.traceparent = v;
  }

  public String getTraceState() {
    return traceState;
  }

  public void setTraceState(String v) {
    this.traceState = v;
  }
}
