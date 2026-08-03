package com.example.samples.s17.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * A line row. It carries the line's own identity, so a write can update the line that changed instead
 * of replacing the whole collection.
 */
@TableName("s17_order_line")
class OrderLineRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String orderId;

  private String sku;

  private String unitPriceCurrency;

  private Long unitPriceAmountCents;

  private Integer quantity;

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

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  String getUnitPriceCurrency() {
    return unitPriceCurrency;
  }

  void setUnitPriceCurrency(String unitPriceCurrency) {
    this.unitPriceCurrency = unitPriceCurrency;
  }

  Long getUnitPriceAmountCents() {
    return unitPriceAmountCents;
  }

  void setUnitPriceAmountCents(Long unitPriceAmountCents) {
    this.unitPriceAmountCents = unitPriceAmountCents;
  }

  Integer getQuantity() {
    return quantity;
  }

  void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }
}
