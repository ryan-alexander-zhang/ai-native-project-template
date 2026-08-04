package com.example.samples.s22.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The order row. Mapping detail is S17. */
@TableName("s22_order")
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;

  private String sku;

  private Integer quantity;

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

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public void setVersion(Long version) {
    this.version = version;
  }
}
