package com.example.samples.s23.billing.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The invoice row. */
@TableName("s23_invoice")
class InvoiceRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String orderId;

  private Long amountMinor;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getOrderId() {
    return orderId;
  }

  void setOrderId(String orderId) {
    this.orderId = orderId;
  }

  Long getAmountMinor() {
    return amountMinor;
  }

  void setAmountMinor(Long amountMinor) {
    this.amountMinor = amountMinor;
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
