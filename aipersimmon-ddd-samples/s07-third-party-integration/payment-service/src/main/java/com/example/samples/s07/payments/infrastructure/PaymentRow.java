package com.example.samples.s07.payments.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * The payment row. {@code @Version} is what makes a callback and a reconciliation touching the same
 * payment at the same moment safe: one of them writes, the other matches zero rows and is retried
 * against the state the winner left. Mapping detail is S17's subject.
 */
@TableName("s07_payment")
class PaymentRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String orderRef;

  private Long amountMinor;

  private Instant requestedAt;

  private String status;

  private String gatewayRef;

  private String reviewReason;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getOrderRef() {
    return orderRef;
  }

  void setOrderRef(String orderRef) {
    this.orderRef = orderRef;
  }

  Long getAmountMinor() {
    return amountMinor;
  }

  void setAmountMinor(Long amountMinor) {
    this.amountMinor = amountMinor;
  }

  Instant getRequestedAt() {
    return requestedAt;
  }

  void setRequestedAt(Instant requestedAt) {
    this.requestedAt = requestedAt;
  }

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
  }

  String getGatewayRef() {
    return gatewayRef;
  }

  void setGatewayRef(String gatewayRef) {
    this.gatewayRef = gatewayRef;
  }

  String getReviewReason() {
    return reviewReason;
  }

  void setReviewReason(String reviewReason) {
    this.reviewReason = reviewReason;
  }

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public void setVersion(Long version) {
    this.version = version;
  }
}
