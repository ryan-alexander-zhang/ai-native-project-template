package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** One order line. */
@TableName("s12_order_line")
class OrderLineRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String orderId;
  private String sku;
  private Integer quantity;
  private Long unitPriceMinor;
  private String nameAtPurchase;

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

  Integer getQuantity() {
    return quantity;
  }

  void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  Long getUnitPriceMinor() {
    return unitPriceMinor;
  }

  void setUnitPriceMinor(Long unitPriceMinor) {
    this.unitPriceMinor = unitPriceMinor;
  }

  String getNameAtPurchase() {
    return nameAtPurchase;
  }

  void setNameAtPurchase(String nameAtPurchase) {
    this.nameAtPurchase = nameAtPurchase;
  }
}
