package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * A projection row. No version column, deliberately.
 *
 * <p>An optimistic lock protects an invariant against concurrent editors, and this table has neither: it is
 * derived, it has one writer, and the correct response to a conflict is to recompute rather than to refuse.
 * Putting {@code @Version} here would make the framework's aggregate base class applicable, which is exactly
 * the wrong signal — a projection is not an aggregate and must not be saved through one.
 */
@TableName("s12_order_list")
class OrderListRow {

  @TableId(type = IdType.INPUT)
  private String orderId;

  private String customerId;
  private String status;
  private Instant placedAt;
  private Instant paidAt;
  private Integer lineCount;
  private Long totalMinor;
  private String displaySummary;
  private Instant projectedAt;

  String getOrderId() {
    return orderId;
  }

  void setOrderId(String orderId) {
    this.orderId = orderId;
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

  Instant getPlacedAt() {
    return placedAt;
  }

  void setPlacedAt(Instant placedAt) {
    this.placedAt = placedAt;
  }

  Instant getPaidAt() {
    return paidAt;
  }

  void setPaidAt(Instant paidAt) {
    this.paidAt = paidAt;
  }

  Integer getLineCount() {
    return lineCount;
  }

  void setLineCount(Integer lineCount) {
    this.lineCount = lineCount;
  }

  Long getTotalMinor() {
    return totalMinor;
  }

  void setTotalMinor(Long totalMinor) {
    this.totalMinor = totalMinor;
  }

  String getDisplaySummary() {
    return displaySummary;
  }

  void setDisplaySummary(String displaySummary) {
    this.displaySummary = displaySummary;
  }

  Instant getProjectedAt() {
    return projectedAt;
  }

  void setProjectedAt(Instant projectedAt) {
    this.projectedAt = projectedAt;
  }
}
