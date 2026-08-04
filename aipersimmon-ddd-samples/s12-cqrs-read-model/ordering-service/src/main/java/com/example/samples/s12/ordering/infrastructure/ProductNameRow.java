package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** This context's replica of one product name. No version column: it is not an aggregate. */
@TableName("s12_product_name")
class ProductNameRow {

  @TableId(type = IdType.INPUT)
  private String sku;

  private String name;
  private Instant updatedAt;

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
