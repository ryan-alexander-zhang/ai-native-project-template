package com.example.samples.s02.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The order row. {@code client_reference} carries a UNIQUE index — see the migration. */
@TableName("s02_order")
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String clientReference;

  private Long amountCents;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getClientReference() {
    return clientReference;
  }

  void setClientReference(String clientReference) {
    this.clientReference = clientReference;
  }

  Long getAmountCents() {
    return amountCents;
  }

  void setAmountCents(Long amountCents) {
    this.amountCents = amountCents;
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
