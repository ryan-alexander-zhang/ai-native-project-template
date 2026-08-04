package com.example.samples.s09.ticketing.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** A child row: one movement of money. The reference is the primary key, which is the idempotency. */
@TableName("s09_wallet_entry")
class WalletEntryRow {

  @TableId(type = IdType.INPUT)
  private String reference;

  private String customerId;
  private String kind;
  private Long amountMinor;
  private String note;
  private Instant recordedAt;

  String getReference() {
    return reference;
  }

  void setReference(String reference) {
    this.reference = reference;
  }

  String getCustomerId() {
    return customerId;
  }

  void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  String getKind() {
    return kind;
  }

  void setKind(String kind) {
    this.kind = kind;
  }

  Long getAmountMinor() {
    return amountMinor;
  }

  void setAmountMinor(Long amountMinor) {
    this.amountMinor = amountMinor;
  }

  String getNote() {
    return note;
  }

  void setNote(String note) {
    this.note = note;
  }

  Instant getRecordedAt() {
    return recordedAt;
  }

  void setRecordedAt(Instant recordedAt) {
    this.recordedAt = recordedAt;
  }
}
