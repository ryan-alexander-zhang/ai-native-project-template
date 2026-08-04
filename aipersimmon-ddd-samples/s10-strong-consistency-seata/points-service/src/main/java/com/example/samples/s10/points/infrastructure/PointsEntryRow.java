package com.example.samples.s10.points.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** One ledger entry, keyed by the caller's reference. */
@TableName("s10_points_entry")
class PointsEntryRow {

  @TableId(type = IdType.INPUT)
  private String reference;

  private String accountId;
  private Integer points;
  private String state;

  String getReference() {
    return reference;
  }

  void setReference(String reference) {
    this.reference = reference;
  }

  String getAccountId() {
    return accountId;
  }

  void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  Integer getPoints() {
    return points;
  }

  void setPoints(Integer points) {
    this.points = points;
  }

  String getState() {
    return state;
  }

  void setState(String state) {
    this.state = state;
  }
}
