package com.example.samples.s25.refunds.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;

/**
 * The row, mapped onto {@code legacy_refunds} — a table with three columns the aggregate does not own.
 *
 * <p>{@code created_at} and {@code updated_at} have database defaults and are maintained (inconsistently) by the
 * monolith. They are annotated {@link TableField}{@code (exist = false)}<strong>-adjacent</strong> in spirit but not in
 * fact: they are simply <em>absent from this class</em>, which is the important part. Had they been fields that
 * {@code toRow} left null, the library's {@code ClearedColumns} would have forced them to NULL on every update —
 * correctly, because for an aggregate-owned column a null means "cleared" — and a {@code NOT NULL} default would have
 * turned that into a failed write. Leaving a column out of the row class entirely is how you say "not mine".
 *
 * <p>{@code approvedBy} is nullable and <em>is</em> owned: a rejection clears it, and the framework forcing that null
 * onto the update is exactly what should happen.
 *
 * <p>{@code IdType.INPUT} on a {@code BIGSERIAL} column, because the application supplies the id — see
 * {@code RefundIds} for why it has to.
 *
 * <p>{@code autoResultMap = true} and an explicit handler on {@code publicId}, because MyBatis has no type handler for
 * PostgreSQL's {@code uuid} — see {@code UuidTypeHandler}. Without the flag the handler applies on the way in and not on
 * the way out, which reads the column back as null and is the more confusing half of the same problem.
 */
@TableName(value = "legacy_refunds", autoResultMap = true)
class RefundRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private Long id;

  private Long orderId;
  private Long amountCents;
  private String reason;
  @TableField(typeHandler = UuidTypeHandler.class)
  private UUID publicId;
  private String state;
  private String approvedBy;

  @Version private Long version;

  Long getId() {
    return id;
  }

  void setId(Long id) {
    this.id = id;
  }

  Long getOrderId() {
    return orderId;
  }

  void setOrderId(Long orderId) {
    this.orderId = orderId;
  }

  Long getAmountCents() {
    return amountCents;
  }

  void setAmountCents(Long amountCents) {
    this.amountCents = amountCents;
  }

  String getReason() {
    return reason;
  }

  void setReason(String reason) {
    this.reason = reason;
  }

  UUID getPublicId() {
    return publicId;
  }

  void setPublicId(UUID publicId) {
    this.publicId = publicId;
  }

  String getState() {
    return state;
  }

  void setState(String state) {
    this.state = state;
  }

  String getApprovedBy() {
    return approvedBy;
  }

  void setApprovedBy(String approvedBy) {
    this.approvedBy = approvedBy;
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
