package com.example.samples.s04.inventory.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** One sku's row. The sku is the key: a natural key, so nothing generates it. */
@TableName("s04_stock")
class StockRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String sku;

  private Integer available;

  private Integer reserved;

  @Version private Long version;

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  Integer getAvailable() {
    return available;
  }

  void setAvailable(Integer available) {
    this.available = available;
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
