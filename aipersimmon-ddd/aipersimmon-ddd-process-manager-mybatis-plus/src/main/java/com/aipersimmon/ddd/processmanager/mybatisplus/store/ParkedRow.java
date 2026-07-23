package com.aipersimmon.ddd.processmanager.mybatisplus.store;

/** Flat projection of a parked-input transition row. */
public class ParkedRow {
  private String inputMessageId;
  private String inputType;
  private int inputVersion;
  private String inputPayload;
  private String correlationId;

  public String getInputMessageId() {
    return inputMessageId;
  }

  public void setInputMessageId(String v) {
    this.inputMessageId = v;
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
}
