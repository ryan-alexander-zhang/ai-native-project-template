package com.example.samples.s10.points.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * The points account row.
 *
 * <p>{@code @TableId} names {@code account_id} only, while the table's key is {@code (tenant_id,
 * account_id)}. That is not an inconsistency: the tenant half of the key is supplied by the tenant-line
 * interceptor on every statement, so the framework's update keys on the identity the aggregate knows and
 * the interceptor completes it. The composite key is what makes a missing predicate loud rather than
 * silent — with a single-column key it would have returned another tenant's row.
 */
@TableName("s10_points_account")
class PointsAccountRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String accountId;

  private Integer awarded;
  private Integer frozen;

  @Version private Long version;

  String getAccountId() {
    return accountId;
  }

  void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  Integer getAwarded() {
    return awarded;
  }

  void setAwarded(Integer awarded) {
    this.awarded = awarded;
  }

  Integer getFrozen() {
    return frozen;
  }

  void setFrozen(Integer frozen) {
    this.frozen = frozen;
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
