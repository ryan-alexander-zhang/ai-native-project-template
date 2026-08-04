package com.example.samples.s12.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/** The order row. */
@TableName("s12_order")
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;
  private String status;
  private Instant placedAt;
  private Instant paidAt;
  private Long totalMinor;

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

  Instant getPlacedAt() {
    return placedAt;
  }

  void setPlacedAt(Instant placedAt) {
    this.placedAt = placedAt;
  }

  Instant getPaidAt() {
    return paidAt;
  }

  void setPaidAt(Instant paidAt) {
    this.paidAt = paidAt;
  }

  Long getTotalMinor() {
    return totalMinor;
  }

  void setTotalMinor(Long totalMinor) {
    this.totalMinor = totalMinor;
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
