package com.example.samples.s26.catalog.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * A sold line.
 *
 * <p>No version column, because nothing ever updates one. The library's {@code VersionedRow} and its
 * optimistic-lock check are for aggregate roots; adding them to an append-only fact table would be
 * ceremony with a cost — an extra column on the highest-volume table in the schema.
 */
@TableName("s26_order_line")
class OrderLineRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String sku;

  private Integer quantity;

  private Instant placedAt;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
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

  Instant getPlacedAt() {
    return placedAt;
  }

  void setPlacedAt(Instant placedAt) {
    this.placedAt = placedAt;
  }
}
