package com.example.samples.s09.ticketing.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** A child row: one order's hold, with the time it was released if it was. */
@TableName("s09_seat_hold")
class SeatHoldRow {

  @TableId(type = IdType.INPUT)
  private String orderId;

  private String seatClass;
  private Instant heldAt;
  private Instant releasedAt;

  String getOrderId() {
    return orderId;
  }

  void setOrderId(String orderId) {
    this.orderId = orderId;
  }

  String getSeatClass() {
    return seatClass;
  }

  void setSeatClass(String seatClass) {
    this.seatClass = seatClass;
  }

  Instant getHeldAt() {
    return heldAt;
  }

  void setHeldAt(Instant heldAt) {
    this.heldAt = heldAt;
  }

  Instant getReleasedAt() {
    return releasedAt;
  }

  void setReleasedAt(Instant releasedAt) {
    this.releasedAt = releasedAt;
  }
}
