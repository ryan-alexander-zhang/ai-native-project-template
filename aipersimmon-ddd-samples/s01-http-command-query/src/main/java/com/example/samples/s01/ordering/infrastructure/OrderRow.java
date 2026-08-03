package com.example.samples.s01.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * The order root row.
 *
 * <p>{@code @TableId} is what the update keys on, and {@code @Version} is what makes the
 * {@code WHERE version = ?} predicate happen. Drop either and the write silently loses its
 * protection — which is why the repository base class refuses the write instead of letting that pass.
 */
@TableName("s01_order")
class OrderRow implements VersionedRow {

  /** INPUT because the application mints the id itself, from the framework's UUIDv7 generator. */
  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;

  private String status;

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

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
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
