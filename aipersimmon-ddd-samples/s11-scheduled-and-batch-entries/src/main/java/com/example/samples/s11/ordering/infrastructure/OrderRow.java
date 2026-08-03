package com.example.samples.s11.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/** The order row. {@code @Version} is what makes two instances sweeping at once safe. */
@TableName("s11_order")
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;

  private String status;

  private Instant paymentDueAt;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getCustomerId() {
    return customerId;
  }

  void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
  }

  Instant getPaymentDueAt() {
    return paymentDueAt;
  }

  void setPaymentDueAt(Instant paymentDueAt) {
    this.paymentDueAt = paymentDueAt;
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
