package com.example.samples.s24.inventory.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The stock row. */
@TableName("s24_inventory_stock_item")
class StockItemRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String sku;

  private Integer onHand;
  private Integer reserved;

  @Version private Long version;

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  Integer getOnHand() {
    return onHand;
  }

  void setOnHand(Integer onHand) {
    this.onHand = onHand;
  }

  Integer getReserved() {
    return reserved;
  }

  void setReserved(Integer reserved) {
    this.reserved = reserved;
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
