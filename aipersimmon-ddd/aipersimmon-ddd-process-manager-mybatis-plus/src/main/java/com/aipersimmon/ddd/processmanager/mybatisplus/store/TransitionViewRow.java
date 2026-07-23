package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;

/** Flat projection of a transition timeline row. */
public class TransitionViewRow {
  private String transitionId;
  private String inputMessageId;
  private String fromLifecycle;
  private String toLifecycle;
  private String fromStep;
  private String toStep;
  private String decisionCode;
  private String transitionKind;
  private String operatorId;
  private String operationReason;
  private Timestamp createdAt;

  public String getTransitionId() {
    return transitionId;
  }

  public void setTransitionId(String v) {
    this.transitionId = v;
  }

  public String getInputMessageId() {
    return inputMessageId;
  }

  public void setInputMessageId(String v) {
    this.inputMessageId = v;
  }

  public String getFromLifecycle() {
    return fromLifecycle;
  }

  public void setFromLifecycle(String v) {
    this.fromLifecycle = v;
  }

  public String getToLifecycle() {
    return toLifecycle;
  }

  public void setToLifecycle(String v) {
    this.toLifecycle = v;
  }

  public String getFromStep() {
    return fromStep;
  }

  public void setFromStep(String v) {
    this.fromStep = v;
  }

  public String getToStep() {
    return toStep;
  }

  public void setToStep(String v) {
    this.toStep = v;
  }

  public String getDecisionCode() {
    return decisionCode;
  }

  public void setDecisionCode(String v) {
    this.decisionCode = v;
  }

  public String getTransitionKind() {
    return transitionKind;
  }

  public void setTransitionKind(String v) {
    this.transitionKind = v;
  }

  public String getOperatorId() {
    return operatorId;
  }

  public void setOperatorId(String v) {
    this.operatorId = v;
  }

  public String getOperationReason() {
    return operationReason;
  }

  public void setOperationReason(String v) {
    this.operationReason = v;
  }

  public Timestamp getCreatedAt() {
    return createdAt == null ? null : (Timestamp) createdAt.clone();
  }

  public void setCreatedAt(Timestamp v) {
    this.createdAt = v == null ? null : (Timestamp) v.clone();
  }
}
