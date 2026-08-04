package com.example.samples.s09.ticketing.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The seat-class root row: the counter two concurrent orders compete for. */
@TableName("s09_seat_class")
class SeatClassRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String seatClass;

  private Integer available;

  @Version private Long version;

  String getSeatClass() {
    return seatClass;
  }

  void setSeatClass(String seatClass) {
    this.seatClass = seatClass;
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
