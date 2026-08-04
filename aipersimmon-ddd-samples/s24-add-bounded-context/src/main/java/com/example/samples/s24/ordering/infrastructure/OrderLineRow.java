package com.example.samples.s24.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;

/** An order line row. A child of the order aggregate, rewritten with it. */
@TableName("s24_ordering_order_line")
class OrderLineRow {

  private String orderId;
  private Integer lineNo;
  private String sku;
  private Integer quantity;
  private Long unitMinor;

  String getOrderId() {
    return orderId;
  }

  void setOrderId(String orderId) {
    this.orderId = orderId;
  }

  Integer getLineNo() {
    return lineNo;
  }

  void setLineNo(Integer lineNo) {
    this.lineNo = lineNo;
  }

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  Integer getQuantity() {
    return quantity;
  }

  void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  Long getUnitMinor() {
    return unitMinor;
  }

  void setUnitMinor(Long unitMinor) {
    this.unitMinor = unitMinor;
  }
}
