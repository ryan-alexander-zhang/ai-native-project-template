package com.example.samples.s24.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/** The order row. */
@TableName("s24_ordering_order")
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;
  private String status;
  private Long grossMinor;
  private Long discountMinor;
  private String currency;
  private String couponCode;
  private Instant placedAt;

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

  Long getGrossMinor() {
    return grossMinor;
  }

  void setGrossMinor(Long grossMinor) {
    this.grossMinor = grossMinor;
  }

  Long getDiscountMinor() {
    return discountMinor;
  }

  void setDiscountMinor(Long discountMinor) {
    this.discountMinor = discountMinor;
  }

  String getCurrency() {
    return currency;
  }

  void setCurrency(String currency) {
    this.currency = currency;
  }

  String getCouponCode() {
    return couponCode;
  }

  void setCouponCode(String couponCode) {
    this.couponCode = couponCode;
  }

  Instant getPlacedAt() {
    return placedAt;
  }

  void setPlacedAt(Instant placedAt) {
    this.placedAt = placedAt;
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
