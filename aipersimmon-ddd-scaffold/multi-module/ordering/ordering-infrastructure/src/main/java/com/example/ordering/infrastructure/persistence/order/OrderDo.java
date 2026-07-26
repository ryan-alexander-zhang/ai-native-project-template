package com.example.ordering.infrastructure.persistence.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** MyBatis-Plus data object for the {@code ordering.orders} header row. */
@TableName("ordering.orders")
public class OrderDo {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;
  private String status;

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

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
