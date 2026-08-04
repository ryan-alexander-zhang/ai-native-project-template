package com.example.samples.s27.customer.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * The customer row, carrying all three deletions.
 *
 * <p>{@code status}, {@code closedReason} and {@code erasedAt} are ordinary mapped fields: the aggregate owns
 * them, {@code toRow} writes them, and they mean what the domain says they mean.
 *
 * <p>{@code deleted} is {@link TableLogic}, and is <strong>deliberately absent from {@code toRow}</strong>. The
 * annotation is what makes that safe: MyBatis-Plus keeps a logic-delete column out of an ordinary update's
 * {@code SET} clause, and the library's {@code ClearedColumns} knows to leave it alone too rather than forcing
 * it to null along with the other unmapped columns. Take the annotation off and the same omission silently
 * un-deletes the row on the next save — which is the trap {@code ClearedColumnsTest} measures, and the reason
 * "is this a domain field or an infrastructure column" has a mechanical answer and not only a modelling one.
 */
@TableName("s27_customer")
class CustomerRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String email;

  private String displayName;

  private String phone;

  private String status;

  private String closedReason;

  private Instant erasedAt;

  /** The infrastructure switch. Never written by {@code toRow}; see the class javadoc. */
  @TableLogic private Boolean deleted;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getEmail() {
    return email;
  }

  void setEmail(String email) {
    this.email = email;
  }

  String getDisplayName() {
    return displayName;
  }

  void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  String getPhone() {
    return phone;
  }

  void setPhone(String phone) {
    this.phone = phone;
  }

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
  }

  String getClosedReason() {
    return closedReason;
  }

  void setClosedReason(String closedReason) {
    this.closedReason = closedReason;
  }

  Instant getErasedAt() {
    return erasedAt;
  }

  void setErasedAt(Instant erasedAt) {
    this.erasedAt = erasedAt;
  }

  Boolean getDeleted() {
    return deleted;
  }

  void setDeleted(Boolean deleted) {
    this.deleted = deleted;
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
