package com.aipersimmon.ddd.processmanager.mybatisplus.store;

/** Flat projection of an effect row loaded for dispatch. */
public class EffectLoadRow {
  private String effectId;
  private String instanceId;
  private String effectKind;
  private String payloadType;
  private int payloadVersion;
  private String payload;
  private String messageId;
  private String correlationId;
  private String causationId;
  private int attempts;
  private String traceparent;
  private String traceState;

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

  public String getPayloadType() {
    return payloadType;
  }

  public void setPayloadType(String v) {
    this.payloadType = v;
  }

  public int getPayloadVersion() {
    return payloadVersion;
  }

  public void setPayloadVersion(int v) {
    this.payloadVersion = v;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String v) {
    this.payload = v;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String v) {
    this.messageId = v;
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
