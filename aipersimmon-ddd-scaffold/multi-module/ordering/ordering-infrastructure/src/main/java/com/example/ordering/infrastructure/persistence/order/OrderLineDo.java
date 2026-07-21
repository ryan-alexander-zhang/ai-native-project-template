package com.example.ordering.infrastructure.persistence.order;

import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus data object for one {@code ordering.order_lines} row (a child of an order). */
@TableName("ordering.order_lines")
public class OrderLineDo {

  private String orderId;
  private Integer lineNo;
  private String sku;
  private Integer quantity;
  private Long unitMinor;
  private String currency;

  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(String orderId) {
    this.orderId = orderId;
  }

  public Integer getLineNo() {
    return lineNo;
  }

  public void setLineNo(Integer lineNo) {
    this.lineNo = lineNo;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public Long getUnitMinor() {
    return unitMinor;
  }

  public void setUnitMinor(Long unitMinor) {
    this.unitMinor = unitMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }
}
