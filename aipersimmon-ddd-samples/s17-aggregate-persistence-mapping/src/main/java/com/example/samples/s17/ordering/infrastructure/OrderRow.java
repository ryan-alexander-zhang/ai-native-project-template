package com.example.samples.s17.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.example.samples.s17.ordering.domain.ShippingAddress;

/**
 * The root row.
 *
 * <p>{@code autoResultMap = true} is what makes the JSON column work on the way back: without it the
 * type handler is used for writing and ignored for reading, and the address comes back null.
 */
@TableName(value = "s17_order", autoResultMap = true)
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;

  private String status;

  /** Nullable, and the whole reason the base class exists — see the doc's cleared-column section. */
  private String note;

  /** A value object serialised whole, because nothing queries its parts. */
  @TableField(typeHandler = JsonbTypeHandler.class)
  private ShippingAddress shippingAddress;

  /** A value object flattened, because the total is queried and summed. */
  private String totalCurrency;

  private Long totalAmountCents;

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

  String getNote() {
    return note;
  }

  void setNote(String note) {
    this.note = note;
  }

  ShippingAddress getShippingAddress() {
    return shippingAddress;
  }

  void setShippingAddress(ShippingAddress shippingAddress) {
    this.shippingAddress = shippingAddress;
  }

  String getTotalCurrency() {
    return totalCurrency;
  }

  void setTotalCurrency(String totalCurrency) {
    this.totalCurrency = totalCurrency;
  }

  Long getTotalAmountCents() {
    return totalAmountCents;
  }

  void setTotalAmountCents(Long totalAmountCents) {
    this.totalAmountCents = totalAmountCents;
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
