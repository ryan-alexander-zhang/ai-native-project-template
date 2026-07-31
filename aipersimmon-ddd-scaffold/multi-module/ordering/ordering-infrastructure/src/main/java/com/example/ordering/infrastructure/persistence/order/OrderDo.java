package com.example.ordering.infrastructure.persistence.order;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/** MyBatis-Plus data object for the {@code ordering.orders} header row. */
@TableName("ordering.orders")
public class OrderDo implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;
  private String status;

  /**
   * The order's total, frozen at write time from {@code Order.total()} (issue-00083). The read
   * model selects these two columns instead of re-deriving the total in SQL, so the rule has one
   * definition — the aggregate's — and the list query needs no join.
   */
  private Long totalMinor;

  private String currency;

  /**
   * When the order was placed, written once from the application Clock at insert (issue-00146).
   * {@code FieldStrategy.NEVER} keeps every later save from touching it: creation time is a fact,
   * not state.
   */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Instant createdAt;

  /**
   * Optimistic-lock version. {@code @Version} makes the MyBatis-Plus
   * OptimisticLockerInnerInterceptor rewrite an {@code updateById} into {@code SET version =
   * version + 1 ... WHERE version = ?}, so a writer working from a stale snapshot updates 0 rows
   * instead of overwriting a concurrent change.
   */
  @Version private Long version;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Long getTotalMinor() {
    return totalMinor;
  }

  public void setTotalMinor(Long totalMinor) {
    this.totalMinor = totalMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
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
