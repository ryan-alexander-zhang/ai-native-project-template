package com.example.samples.s08.inventory.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The budget row: one row, and therefore one serialisation point for the rule it owns. */
@TableName("s08_reservation_budget")
class BudgetRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private Integer limitUnits;

  private Integer reservedUnits;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  Integer getLimitUnits() {
    return limitUnits;
  }

  void setLimitUnits(Integer limitUnits) {
    this.limitUnits = limitUnits;
  }

  Integer getReservedUnits() {
    return reservedUnits;
  }

  void setReservedUnits(Integer reservedUnits) {
    this.reservedUnits = reservedUnits;
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
