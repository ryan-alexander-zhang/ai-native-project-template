package com.aipersimmon.ddd.processmanager.mybatisplus.store;

/**
 * Flat projection of an {@code aipersimmon_process_instance} row for MyBatis result mapping
 * (snake_case columns to these camelCase fields). The store converts it to the engine's {@code
 * ProcessInstanceRow} value object, mirroring the JDBC RowMapper.
 */
public class InstanceRow {
  private String instanceId;
  private String processType;
  private String businessKey;
  private String definitionVersion;
  private int stateSchemaVersion;
  private String lifecycle;
  private String resumeLifecycle;
  private String suspensionReason;
  private String businessStep;
  private String outcome;
  private long revision;
  private String statePayloadType;
  private String statePayload;

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String v) {
    this.instanceId = v;
  }

  public String getProcessType() {
    return processType;
  }

  public void setProcessType(String v) {
    this.processType = v;
  }

  public String getBusinessKey() {
    return businessKey;
  }

  public void setBusinessKey(String v) {
    this.businessKey = v;
  }

  public String getDefinitionVersion() {
    return definitionVersion;
  }

  public void setDefinitionVersion(String v) {
    this.definitionVersion = v;
  }

  public int getStateSchemaVersion() {
    return stateSchemaVersion;
  }

  public void setStateSchemaVersion(int v) {
    this.stateSchemaVersion = v;
  }

  public String getLifecycle() {
    return lifecycle;
  }

  public void setLifecycle(String v) {
    this.lifecycle = v;
  }

  public String getResumeLifecycle() {
    return resumeLifecycle;
  }

  public void setResumeLifecycle(String v) {
    this.resumeLifecycle = v;
  }

  public String getSuspensionReason() {
    return suspensionReason;
  }

  public void setSuspensionReason(String v) {
    this.suspensionReason = v;
  }

  public String getBusinessStep() {
    return businessStep;
  }

  public void setBusinessStep(String v) {
    this.businessStep = v;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String v) {
    this.outcome = v;
  }

  public long getRevision() {
    return revision;
  }

  public void setRevision(long v) {
    this.revision = v;
  }

  public String getStatePayloadType() {
    return statePayloadType;
  }

  public void setStatePayloadType(String v) {
    this.statePayloadType = v;
  }

  public String getStatePayload() {
    return statePayload;
  }

  public void setStatePayload(String v) {
    this.statePayload = v;
  }
}
