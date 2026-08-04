package com.example.samples.s09.ticketing.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The order row. Mapping detail is S17. */
@TableName("s09_ticket_order")
class TicketOrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;
  private String seatClass;
  private Long amountMinor;
  private String status;
  private String cancelReason;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getCustomerId() {
    return customerId;
  }

  void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  String getSeatClass() {
    return seatClass;
  }

  void setSeatClass(String seatClass) {
    this.seatClass = seatClass;
  }

  Long getAmountMinor() {
    return amountMinor;
  }

  void setAmountMinor(Long amountMinor) {
    this.amountMinor = amountMinor;
  }

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
  }

  String getCancelReason() {
    return cancelReason;
  }

  void setCancelReason(String cancelReason) {
    this.cancelReason = cancelReason;
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
