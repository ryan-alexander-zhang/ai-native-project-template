package com.example.samples.s08.inventory.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The stock row. Its version is what stops two reservations of the same sku from both winning. */
@TableName("s08_stock")
class StockRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String sku;

  private Integer available;

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

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public void setVersion(Long version) {
    this.version = version;
  }
}
