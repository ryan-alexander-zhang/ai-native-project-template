package com.example.samples.s21.inventory.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * One location's row, keyed by the composite {@code sku@warehouse}. The two parts are also kept as
 * their own columns, because a key is for finding a row and columns are for querying — S17 is the
 * sample that argues that properly.
 */
@TableName("s21_stock")
class StockRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String location;

  private String sku;

  private String warehouse;

  private Integer available;

  private Integer reserved;

  @Version private Long version;

  String getLocation() {
    return location;
  }

  void setLocation(String location) {
    this.location = location;
  }

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  String getWarehouse() {
    return warehouse;
  }

  void setWarehouse(String warehouse) {
    this.warehouse = warehouse;
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
