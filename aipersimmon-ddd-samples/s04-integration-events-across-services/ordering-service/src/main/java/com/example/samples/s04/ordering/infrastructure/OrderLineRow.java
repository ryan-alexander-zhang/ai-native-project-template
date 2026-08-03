package com.example.samples.s04.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** A child row. No version of its own: the root is the concurrency unit. */
@TableName("s04_order_line")
class OrderLineRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String orderId;

  private String sku;

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

  Integer getQuantity() {
    return quantity;
  }

  void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }
}
